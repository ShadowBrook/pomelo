# 媒体消息实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让客户端能发送图片、自定义表情、视频、音频、文件消息：SDK 先 presigned PUT 直传 MinIO，消息正文只带「稳定 object key + 元数据」，服务端读侧注入 presigned GET URL。

**Architecture:** 新增 `CMD_UPLOAD_REQ/RESP` 命令走 gateway→logic EventBus，`UploadService` 签发 presigned PUT；`content` 字段存字符串化 JSON（key + 元数据），零 DB 迁移；`MediaUrlSigner` 在 4 个服务的出站路径（8 处）把 key 换成 presigned GET URL。

**Tech Stack:** Java 17、Vert.x 5.1.5、Maven、protobuf 4.31.1、`software.amazon.awssdk:s3`（S3Presigner）、Testcontainers（MinIO）。

**Spec:** `docs/superpowers/specs/2026-08-27-media-message-design.md`

## Global Constraints

- Java 17；Vert.x 版本以父 pom `<vertx.version>5.1.5</vertx.version>` 为准。
- 编码风格：代码体**禁用全限定类名**（用 import）；**禁用行尾注释**（注释独立成行）。
- 媒体 `content` 落库**只存 key，不存 url**；url 由 `MediaUrlSigner` 读侧注入。
- 媒体 `msgType ∈ {2=IMAGE, 3=VOICE, 4=VIDEO, 5=FILE, 6=EMOJI}`；`1=TEXT` / `7=SYSTEM` 不走 JSON。
- object key 格式：`{type}/{userId}/{yyyyMMdd}/{snowflakeId}.{ext}`，其中 type ∈ {image,voice,video,file,emoji}。
- presigned GET 默认 TTL = **604800 秒（7 天，S3 presigned 上限）**。
- **零 DB 迁移**：不改 `db/schema.sql`、不改 `MessageRecord`、不改 `C2CService.doSend` / `C2GService.doSend`。
- `ConfigHolder` 未加载时 `getString/getInt/getLong` 会 fallback 到默认值（可安全在测试中调用）；`getConfig()` 未加载会抛异常（不要用）。

---

### Task 1: 协议与命令定义

**Files:**
- Modify: `pomelo-common/src/main/proto/common/common.proto`
- Create: `pomelo-common/src/main/proto/upload/upload.proto`
- Modify: `pomelo-common/src/main/proto/message/message.proto`

**Interfaces:**
- Produces: 生成的 `com.github.moxib.pomelo.proto.upload.UploadProto`（`UploadReq`/`UploadResp`）、`CommonProto.Cmd.CMD_UPLOAD_REQ_VALUE` / `CMD_UPLOAD_RESP_VALUE`（供后续 task 引用）。

- [ ] **Step 1: 在 `common.proto` 的 `Cmd` 枚举加两条命令**

在群管理块（`CMD_GROUP_MSG_READ_RESP = 0x0099;`）之后、`CMD_ERROR` 之前插入：

```proto
    // 媒体上传
    // C→S 申请上传预签名
    CMD_UPLOAD_REQ    = 0x00A0;
    // S→C 返回上传预签名 URL
    CMD_UPLOAD_RESP   = 0x00A1;
```

- [ ] **Step 2: 新建 `upload/upload.proto`**

```proto
syntax = "proto3";

package im.upload;

option java_package = "com.github.moxib.pomelo.proto.upload";
option java_outer_classname = "UploadProto";

// ============================================================================
// 媒体上传预签名
// ============================================================================
message UploadReq {
    // 媒体类型（MsgType: 2=image 3=voice 4=video 5=file 6=emoji）
    int32 media_type    = 1;
    // 原始文件名（含扩展名）
    string file_name    = 2;
    // 文件大小（字节）
    int64 size          = 3;
    // MIME 类型（可选）
    string content_type = 4;
}

message UploadResp {
    // 响应码
    int32 code           = 1;
    // 响应消息
    string message       = 2;
    // 稳定 object key
    string object_key    = 3;
    // presigned PUT URL
    string presigned_url = 4;
    // 过期时间（Unix 毫秒）
    int64 expire_at      = 5;
}
```

- [ ] **Step 3: 在 `message.proto` 的 `MsgBody` oneof 加字段 + import**

在 import 区加一行：

```proto
import "upload/upload.proto";
```

在 oneof 的 ack 块之后加：

```proto
        // 媒体上传
        im.upload.UploadReq upload_req   = 70;
        im.upload.UploadResp upload_resp = 71;
```

- [ ] **Step 4: 重新生成 protobuf Java 源码**

Run: `./mvnw -pl pomelo-common protobuf:compile`

Expected: BUILD SUCCESS，`pomelo-common/target/generated-sources/` 下出现 `UploadProto.java`。

- [ ] **Step 5: Commit**

```bash
git add pomelo-common/src/main/proto/common/common.proto pomelo-common/src/main/proto/upload/upload.proto pomelo-common/src/main/proto/message/message.proto
git commit -m "feat: 新增媒体上传命令 CMD_UPLOAD_REQ/RESP 与 upload.proto"
```

---

### Task 2: UploadRequest DTO + 编解码注册

**Files:**
- Create: `pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/model/requests/UploadRequest.java`
- Modify: `pomelo-common/src/main/java/com/github/moxib/pomelo/codec/ProtobufCodec.java`
- Modify: `pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/codec/CodecRegistryHolder.java`
- Test: `pomelo-logic-server/src/test/java/com/github/moxib/pomelo/logic/codec/UploadCodecRegistrationTest.java`

**Interfaces:**
- Consumes: `UploadProto.UploadReq`（Task 1 生成）。
- Produces: `UploadRequest` record（字段 `int mediaType, String fileName, long size, String contentType` + `static UploadRequest fromProto(UploadProto.UploadReq)`）；`CodecRegistryHolder.REGISTRY` 已注册 `CMD_UPLOAD_REQ` 的 PB/JSON codec。

- [ ] **Step 1: 写失败测试 `UploadCodecRegistrationTest`**

```java
package com.github.moxib.pomelo.logic.codec;

import com.github.moxib.pomelo.logic.model.requests.UploadRequest;
import com.github.moxib.pomelo.proto.common.CommonProto;
import com.github.moxib.pomelo.proto.upload.UploadProto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UploadCodecRegistrationTest {

  @Test
  void uploadReqCodecIsRegisteredForPbAndJson() {
    assertNotNull(CodecRegistryHolder.REGISTRY.getCodec(CommonProto.Cmd.CMD_UPLOAD_REQ_VALUE, 0));
    assertNotNull(CodecRegistryHolder.REGISTRY.getCodec(CommonProto.Cmd.CMD_UPLOAD_REQ_VALUE, 1));
  }

  @Test
  void fromProtoMapsAllFields() {
    UploadProto.UploadReq proto = UploadProto.UploadReq.newBuilder()
      .setMediaType(2).setFileName("a.jpg").setSize(123L).setContentType("image/jpeg").build();
    UploadRequest req = UploadRequest.fromProto(proto);
    assertEquals(2, req.mediaType());
    assertEquals("a.jpg", req.fileName());
    assertEquals(123L, req.size());
    assertEquals("image/jpeg", req.contentType());
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -pl pomelo-logic-server -am test -Dtest=UploadCodecRegistrationTest`

Expected: 编译失败（`UploadRequest` 不存在 / codec 未注册）。

- [ ] **Step 3: 新建 `UploadRequest` record**

```java
package com.github.moxib.pomelo.logic.model.requests;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.github.moxib.pomelo.proto.upload.UploadProto;

/**
 * 媒体上传预签名请求 DTO（PB UploadReq / JSON 共用）。
 * JSON 格式：{mediaType, fileName, size, contentType}。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UploadRequest(
  int mediaType,
  String fileName,
  long size,
  String contentType
) {
  public static UploadRequest fromProto(UploadProto.UploadReq proto) {
    return new UploadRequest(
      proto.getMediaType(),
      proto.getFileName(),
      proto.getSize(),
      proto.getContentType());
  }
}
```

- [ ] **Step 4: 在 `ProtobufCodec` 静态注册两个 parser**

在 `ProtobufCodec` 顶部 import 区加：

```java
import com.github.moxib.pomelo.proto.upload.UploadProto;
```

在 static block 的「群管理」注册之后、`}` 之前插入：

```java
    // 媒体上传
    registerProto(CommonProto.Cmd.CMD_UPLOAD_REQ_VALUE, UploadProto.UploadReq.parser(), UploadProto.UploadReq.class);
    registerProto(CommonProto.Cmd.CMD_UPLOAD_RESP_VALUE, UploadProto.UploadResp.parser(), UploadProto.UploadResp.class);
```

- [ ] **Step 5: 在 `CodecRegistryHolder` 注册 PB DTO + JSON codec**

顶部 import 区加：

```java
import com.github.moxib.pomelo.logic.model.requests.UploadRequest;
import com.github.moxib.pomelo.proto.upload.UploadProto;
```

在 `build()` 方法 return 之前插入：

```java
    // 媒体上传预签名
    r.registerProtobuf(CMD_UPLOAD_REQ_VALUE, UploadProto.UploadReq.parser(),
      UploadRequest::fromProto, UploadRequest.class);
    r.registerJson(CMD_UPLOAD_REQ_VALUE, UploadRequest.class);
```

- [ ] **Step 6: 运行确认通过**

Run: `./mvnw -pl pomelo-logic-server -am test -Dtest=UploadCodecRegistrationTest`

Expected: PASS。

- [ ] **Step 7: Commit**

```bash
git add pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/model/requests/UploadRequest.java \
        pomelo-common/src/main/java/com/github/moxib/pomelo/codec/ProtobufCodec.java \
        pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/codec/CodecRegistryHolder.java \
        pomelo-logic-server/src/test/java/com/github/moxib/pomelo/logic/codec/UploadCodecRegistrationTest.java
git commit -m "feat: UploadRequest DTO + 上传命令编解码注册"
```

---

### Task 3: ObjectPresigner + MinioObjectPresigner + 配置

**Files:**
- Modify: `pomelo-logic-server/pom.xml`
- Create: `pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/infrastructure/ObjectPresigner.java`
- Create: `pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/infrastructure/MinioObjectPresigner.java`
- Modify: `conf/config.yaml`
- Test: `pomelo-logic-server/src/test/java/com/github/moxib/pomelo/logic/infrastructure/MinioObjectPresignerTest.java`

**Interfaces:**
- Produces: `ObjectPresigner` 接口（`String presignPut(String objectKey, String contentType)`、`String presignGet(String objectKey)`），供 Task 4/5 使用。

- [ ] **Step 1: 加 S3 SDK 依赖到 `pomelo-logic-server/pom.xml`**

在 `<dependencies>` 内（`pomelo-seqsvr-client` 依赖之后）加：

```xml
    <dependency>
      <groupId>software.amazon.awssdk</groupId>
      <artifactId>s3</artifactId>
      <version>2.29.52</version>
    </dependency>
```

> 若 `2.29.52` 无法解析，`./mvnw -pl pomelo-logic-server dependency:resolve` 查看可用版本并换成 Maven Central 上最新 2.29.x。

- [ ] **Step 2: 写失败测试 `MinioObjectPresignerTest`（Testcontainers MinIO）**

```java
package com.github.moxib.pomelo.logic.infrastructure;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class MinioObjectPresignerTest {

  private static final String ACCESS_KEY = "minioadmin";
  private static final String SECRET_KEY = "minioadmin";

  @Container
  static final GenericContainer<?> MINIO = new GenericContainer<>("minio/minio:latest")
    .withExposedPorts(9000)
    .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
    .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
    .withCommand("server", "/data");

  private String endpoint() {
    return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
  }

  private void createBucket(String endpoint, String bucket) {
    try (S3Client client = S3Client.builder()
      .region(Region.US_EAST_1)
      .endpointOverride(URI.create(endpoint))
      .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
      .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
      .build()) {
      client.createBucket(b -> b.bucket(bucket));
    }
  }

  @Test
  void presignedPutAndGetRoundTrip() throws Exception {
    String endpoint = endpoint();
    String bucket = "test-bucket";
    createBucket(endpoint, bucket);

    MinioObjectPresigner presigner = new MinioObjectPresigner(endpoint, bucket, ACCESS_KEY, SECRET_KEY, 300, 600);

    String putUrl = presigner.presignPut("a/b.txt", "text/plain");
    HttpClient http = HttpClient.newHttpClient();
    int putStatus = http.send(HttpRequest.newBuilder(URI.create(putUrl))
      .PUT(HttpRequest.BodyPublishers.ofString("hello")).build(),
      HttpResponse.BodyHandlers.ofString()).statusCode();
    assertEquals(200, putStatus, "presigned PUT 应写入成功");

    String getUrl = presigner.presignGet("a/b.txt");
    HttpResponse<String> getResp = http.send(HttpRequest.newBuilder(URI.create(getUrl))
      .GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, getResp.statusCode(), "presigned GET 应读取成功");
    assertEquals("hello", getResp.body());
  }
}
```

- [ ] **Step 3: 运行确认失败**

Run: `./mvnw -pl pomelo-logic-server test -Dtest=MinioObjectPresignerTest`

Expected: 编译失败（`ObjectPresigner`/`MinioObjectPresigner` 不存在）。需本机 Docker 可用。

- [ ] **Step 4: 新建 `ObjectPresigner` 接口**

```java
package com.github.moxib.pomelo.logic.infrastructure;

/**
 * 对象存储预签名器。为对象生成带时效的访问 URL，服务端不代理字节流。
 */
public interface ObjectPresigner {

  /** 生成 presigned PUT URL（上传用） */
  String presignPut(String objectKey, String contentType);

  /** 生成 presigned GET URL（下载用） */
  String presignGet(String objectKey);
}
```

- [ ] **Step 5: 新建 `MinioObjectPresigner`**

```java
package com.github.moxib.pomelo.logic.infrastructure;

import com.github.moxib.pomelo.config.ConfigHolder;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

import java.net.URI;
import java.time.Duration;

/**
 * MinIO / S3 兼容对象存储的预签名实现。
 * presign 为本地 HMAC 运算，无网络往返；path-style 访问是 MinIO 必需。
 */
public class MinioObjectPresigner implements ObjectPresigner {

  private final S3Presigner presigner;
  private final String bucket;
  private final Duration putTtl;
  private final Duration getTtl;

  public MinioObjectPresigner() {
    this(ConfigHolder.getString("media.endpoint", "http://silo:9000"),
         ConfigHolder.getString("media.bucket", "pomelo-media"),
         ConfigHolder.getString("media.accessKey", "pomelo-admin"),
         ConfigHolder.getString("media.secretKey", "pomelo-admin-password"),
         ConfigHolder.getInt("media.putUrlTtlSeconds", 300),
         ConfigHolder.getInt("media.getUrlTtlSeconds", 604800));
  }

  public MinioObjectPresigner(String endpoint, String bucket, String accessKey, String secretKey,
                              int putTtlSeconds, int getTtlSeconds) {
    this.bucket = bucket;
    this.putTtl = Duration.ofSeconds(putTtlSeconds);
    this.getTtl = Duration.ofSeconds(getTtlSeconds);
    this.presigner = S3Presigner.builder()
      .region(Region.US_EAST_1)
      .endpointOverride(URI.create(endpoint))
      .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
      .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
      .build();
  }

  @Override
  public String presignPut(String objectKey, String contentType) {
    PresignedPutObjectRequest req = presigner.presignPutObject(b -> b
      .putObjectRequest(PutObjectRequest.builder()
        .bucket(bucket)
        .key(objectKey)
        .contentType(contentType != null ? contentType : "application/octet-stream")
        .build())
      .signatureDuration(putTtl));
    return req.url().toString();
  }

  @Override
  public String presignGet(String objectKey) {
    PresignedGetObjectRequest req = presigner.presignGetObject(b -> b
      .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(objectKey).build())
      .signatureDuration(getTtl));
    return req.url().toString();
  }
}
```

- [ ] **Step 6: 在 `conf/config.yaml` 末尾加 `media:` 配置块**

```yaml
media:
  endpoint: http://silo:9000
  bucket: pomelo-media
  accessKey: pomelo-admin
  secretKey: pomelo-admin-password
  putUrlTtlSeconds: 300
  getUrlTtlSeconds: 604800
  maxSizeBytes: 104857600
  allowedExtensions: "jpg,jpeg,png,gif,webp,mp4,mov,mp3,m4a,aac,amr,pdf,zip"
```

> 说明：spec 里 `allowedExtensions` 是 YAML 列表，实现改为逗号分隔字符串，方便 `ConfigHolder.getString` 读取（未加载时能 fallback 默认值）。功能等价。

- [ ] **Step 7: 运行确认通过**

Run: `./mvnw -pl pomelo-logic-server test -Dtest=MinioObjectPresignerTest`

Expected: PASS（PUT 200、GET 200、body "hello"）。

- [ ] **Step 8: Commit**

```bash
git add pomelo-logic-server/pom.xml \
        pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/infrastructure/ObjectPresigner.java \
        pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/infrastructure/MinioObjectPresigner.java \
        conf/config.yaml \
        pomelo-logic-server/src/test/java/com/github/moxib/pomelo/logic/infrastructure/MinioObjectPresignerTest.java
git commit -m "feat: MinIO presigned PUT/GET 预签名器 + media 配置"
```

---

### Task 4: MediaUrlSigner + MinioMediaUrlSigner

**Files:**
- Create: `pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/service/MediaUrlSigner.java`
- Create: `pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/service/MinioMediaUrlSigner.java`
- Test: `pomelo-logic-server/src/test/java/com/github/moxib/pomelo/logic/service/MinioMediaUrlSignerTest.java`

**Interfaces:**
- Consumes: `ObjectPresigner`（Task 3）。
- Produces: `MediaUrlSigner`（`String signContent(int msgType, String content)`），供 Task 6 注入 4 个 service。

- [ ] **Step 1: 写失败测试 `MinioMediaUrlSignerTest`**

```java
package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.logic.infrastructure.ObjectPresigner;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MinioMediaUrlSignerTest {

  private static final ObjectPresigner fake = new ObjectPresigner() {
    @Override public String presignPut(String k, String ct) { return "http://put/" + k; }
    @Override public String presignGet(String k) { return "http://get/" + k; }
  };

  private final MediaUrlSigner signer = new MinioMediaUrlSigner(fake);

  @Test
  void injectsUrlForImage() {
    String out = signer.signContent(2, "{\"key\":\"img/1.jpg\",\"width\":1080}");
    JsonObject obj = new JsonObject(out);
    assertEquals("http://get/img/1.jpg", obj.getString("url"));
    assertEquals("img/1.jpg", obj.getString("key"));
  }

  @Test
  void injectsThumbUrlForVideo() {
    String out = signer.signContent(4, "{\"key\":\"v.mp4\",\"thumb\":\"v_thumb.jpg\"}");
    JsonObject obj = new JsonObject(out);
    assertEquals("http://get/v.mp4", obj.getString("url"));
    assertEquals("http://get/v_thumb.jpg", obj.getString("thumbUrl"));
  }

  @Test
  void passesThroughText() {
    String in = "hello";
    assertSame(in, signer.signContent(1, in));
  }

  @Test
  void passesThroughMissingKey() {
    String in = "{\"width\":100}";
    assertEquals(in, signer.signContent(2, in));
  }

  @Test
  void passesThroughBadJson() {
    String in = "not-json";
    assertEquals(in, signer.signContent(2, in));
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -pl pomelo-logic-server -am test -Dtest=MinioMediaUrlSignerTest`

Expected: 编译失败（`MediaUrlSigner`/`MinioMediaUrlSigner` 不存在）。

- [ ] **Step 3: 新建 `MediaUrlSigner` 接口**

```java
package com.github.moxib.pomelo.logic.service;

/**
 * 媒体消息读侧签名器：把 content JSON 里的稳定 key 换成 presigned GET URL。
 */
public interface MediaUrlSigner {

  /**
   * msgType ∈ {2..6} 且 content 可解析出 key 时，注入 url/thumbUrl；否则原样返回。
   * 永不抛异常。
   */
  String signContent(int msgType, String content);
}
```

- [ ] **Step 4: 新建 `MinioMediaUrlSigner`**

```java
package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.logic.infrastructure.ObjectPresigner;
import com.github.moxib.pomelo.proto.common.CommonProto;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MinioMediaUrlSigner implements MediaUrlSigner {

  private static final Logger LOG = LoggerFactory.getLogger(MinioMediaUrlSigner.class);

  private final ObjectPresigner presigner;

  public MinioMediaUrlSigner(ObjectPresigner presigner) {
    this.presigner = presigner;
  }

  @Override
  public String signContent(int msgType, String content) {
    if (!isMediaType(msgType)) {
      return content;
    }
    if (content == null || content.isBlank()) {
      return content;
    }
    try {
      JsonObject obj = new JsonObject(content);
      String key = obj.getString("key");
      if (key == null || key.isBlank()) {
        return content;
      }
      obj.put("url", presigner.presignGet(key));
      String thumb = obj.getString("thumb");
      if (thumb != null && !thumb.isBlank()) {
        obj.put("thumbUrl", presigner.presignGet(thumb));
      }
      return obj.encode();
    } catch (Exception e) {
      LOG.warn("媒体 content 签名失败，原样返回: {}", e.getMessage());
      return content;
    }
  }

  private static boolean isMediaType(int msgType) {
    return msgType == CommonProto.MsgType.MSG_TYPE_IMAGE_VALUE
      || msgType == CommonProto.MsgType.MSG_TYPE_VOICE_VALUE
      || msgType == CommonProto.MsgType.MSG_TYPE_VIDEO_VALUE
      || msgType == CommonProto.MsgType.MSG_TYPE_FILE_VALUE
      || msgType == CommonProto.MsgType.MSG_TYPE_EMOJI_VALUE;
  }
}
```

- [ ] **Step 5: 运行确认通过**

Run: `./mvnw -pl pomelo-logic-server -am test -Dtest=MinioMediaUrlSignerTest`

Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/service/MediaUrlSigner.java \
        pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/service/MinioMediaUrlSigner.java \
        pomelo-logic-server/src/test/java/com/github/moxib/pomelo/logic/service/MinioMediaUrlSignerTest.java
git commit -m "feat: MediaUrlSigner 读侧注入 presigned GET URL"
```

---

### Task 5: UploadService

**Files:**
- Create: `pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/service/UploadService.java`
- Test: `pomelo-logic-server/src/test/java/com/github/moxib/pomelo/logic/service/UploadServiceTest.java`

**Interfaces:**
- Consumes: `ObjectPresigner`（Task 3）、`SnowflakeIdGenerator`、`UploadRequest`（Task 2）。
- Produces: `UploadService`（`Future<ImMessage> process(ImMessage)`），供 Task 7 接入 `logic.upload`。

- [ ] **Step 1: 写失败测试 `UploadServiceTest`**

```java
package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.logic.id.SnowflakeIdGenerator;
import com.github.moxib.pomelo.logic.infrastructure.ObjectPresigner;
import com.github.moxib.pomelo.proto.common.CommonProto;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class UploadServiceTest {

  private static final ObjectPresigner fake = new ObjectPresigner() {
    @Override public String presignPut(String k, String ct) { return "http://put/" + k; }
    @Override public String presignGet(String k) { return "http://get/" + k; }
  };

  private final UploadService service = new UploadService(fake, new SnowflakeIdGenerator(1));

  private ImMessage jsonReq(String bodyJson, String userId) {
    byte[] body = new JsonObject(bodyJson).toBuffer().getBytes();
    Map<String, String> headers = new HashMap<>();
    if (userId != null) {
      headers.put("userId", userId);
    }
    return ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER).version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId((byte) 1).cmd(CommonProto.Cmd.CMD_UPLOAD_REQ_VALUE)
      .messageId("u-1").body(body).varHeaders(headers).build();
  }

  private JsonObject respBody(ImMessage resp) {
    return new JsonObject(new String(resp.getBody(), StandardCharsets.UTF_8));
  }

  private ImMessage process(ImMessage req) throws Exception {
    return service.process(req).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  @Test
  void validRequestReturnsObjectKeyAndPresignedUrl() throws Exception {
    ImMessage resp = process(jsonReq("{\"mediaType\":2,\"fileName\":\"a.jpg\",\"size\":100}", "100"));
    JsonObject body = respBody(resp);
    assertEquals(0, body.getInteger("code"));
    String key = body.getString("objectKey");
    assertTrue(key.matches("image/100/\\d{8}/\\d+\\.jpg"), "key 格式不符: " + key);
    assertEquals("http://put/" + key, body.getString("presignedUrl"));
    assertTrue(body.getLong("expireAt") > 0);
  }

  @Test
  void rejectsUnknownMediaType() throws Exception {
    ImMessage resp = process(jsonReq("{\"mediaType\":1,\"fileName\":\"a.jpg\",\"size\":100}", "100"));
    assertNotEquals(0, respBody(resp).getInteger("code"));
  }

  @Test
  void rejectsOversize() throws Exception {
    ImMessage resp = process(jsonReq("{\"mediaType\":2,\"fileName\":\"a.jpg\",\"size\":999999999}", "100"));
    assertNotEquals(0, respBody(resp).getInteger("code"));
  }

  @Test
  void rejectsDisallowedExtension() throws Exception {
    ImMessage resp = process(jsonReq("{\"mediaType\":2,\"fileName\":\"a.exe\",\"size\":100}", "100"));
    assertNotEquals(0, respBody(resp).getInteger("code"));
  }

  @Test
  void rejectsUnauthenticated() throws Exception {
    ImMessage resp = process(jsonReq("{\"mediaType\":2,\"fileName\":\"a.jpg\",\"size\":100}", null));
    assertNotEquals(0, respBody(resp).getInteger("code"));
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -pl pomelo-logic-server -am test -Dtest=UploadServiceTest`

Expected: 编译失败（`UploadService` 不存在）。

- [ ] **Step 3: 新建 `UploadService`**

```java
package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.config.ConfigHolder;
import com.github.moxib.pomelo.logic.id.SnowflakeIdGenerator;
import com.github.moxib.pomelo.logic.infrastructure.ObjectPresigner;
import com.github.moxib.pomelo.logic.model.requests.UploadRequest;
import com.github.moxib.pomelo.proto.common.CommonProto;
import com.github.moxib.pomelo.proto.upload.UploadProto;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.CMD_UPLOAD_RESP_VALUE;

public class UploadService extends ServiceBase {

  private static final Logger LOG = LoggerFactory.getLogger(UploadService.class);

  private static final String DEFAULT_EXTENSIONS = "jpg,jpeg,png,gif,webp,mp4,mov,mp3,m4a,aac,amr,pdf,zip";

  private final ObjectPresigner presigner;
  private final SnowflakeIdGenerator snowflake;
  private final long maxSizeBytes;
  private final Set<String> allowedExtensions;

  public UploadService(ObjectPresigner presigner, SnowflakeIdGenerator snowflake) {
    this.presigner = presigner;
    this.snowflake = snowflake;
    this.maxSizeBytes = ConfigHolder.getLong("media.maxSizeBytes", 104857600L);
    this.allowedExtensions = loadAllowedExtensions();
  }

  private static Set<String> loadAllowedExtensions() {
    String raw = ConfigHolder.getString("media.allowedExtensions", DEFAULT_EXTENSIONS);
    Set<String> set = new HashSet<>();
    for (String s : raw.split(",")) {
      if (!s.isBlank()) {
        set.add(s.trim().toLowerCase());
      }
    }
    return set;
  }

  public Future<ImMessage> process(ImMessage message) {
    try {
      String userId = getUserIdFromHeaders(message);
      if (userId == null || userId.isEmpty()) {
        return Future.succeededFuture(buildErrorResp(message, CMD_UPLOAD_RESP_VALUE, ErrorCode.UNAUTHORIZED, "未认证用户"));
      }
      UploadRequest req = decode(message, UploadRequest.class);
      if (!isMediaType(req.mediaType())) {
        return Future.succeededFuture(buildErrorResp(message, CMD_UPLOAD_RESP_VALUE, ErrorCode.BAD_REQUEST, "不支持的 media_type"));
      }
      if (req.size() <= 0 || req.size() > maxSizeBytes) {
        return Future.succeededFuture(buildErrorResp(message, CMD_UPLOAD_RESP_VALUE, ErrorCode.BAD_REQUEST, "文件大小超限"));
      }
      String ext = extractExt(req.fileName());
      if (ext == null || !allowedExtensions.contains(ext)) {
        return Future.succeededFuture(buildErrorResp(message, CMD_UPLOAD_RESP_VALUE, ErrorCode.BAD_REQUEST, "文件扩展名不允许"));
      }

      String objectKey = buildObjectKey(req.mediaType(), userId, ext);
      String presignedUrl = presigner.presignPut(objectKey, req.contentType());
      long expireAt = System.currentTimeMillis() + ConfigHolder.getLong("media.putUrlTtlSeconds", 300) * 1000;

      return Future.succeededFuture(buildUploadResp(message, 0, "success", objectKey, presignedUrl, expireAt));
    } catch (Exception e) {
      LOG.error("上传请求处理失败", e);
      return Future.succeededFuture(buildErrorResp(message, CMD_UPLOAD_RESP_VALUE, ErrorCode.INTERNAL_ERROR, "上传预签名失败"));
    }
  }

  private String buildObjectKey(int mediaType, String userId, String ext) {
    String typeDir = mediaTypeDir(mediaType);
    String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
    return typeDir + "/" + userId + "/" + day + "/" + snowflake.nextId() + "." + ext;
  }

  private static String mediaTypeDir(int mediaType) {
    return switch (mediaType) {
      case CommonProto.MsgType.MSG_TYPE_IMAGE_VALUE -> "image";
      case CommonProto.MsgType.MSG_TYPE_VOICE_VALUE -> "voice";
      case CommonProto.MsgType.MSG_TYPE_VIDEO_VALUE -> "video";
      case CommonProto.MsgType.MSG_TYPE_FILE_VALUE -> "file";
      case CommonProto.MsgType.MSG_TYPE_EMOJI_VALUE -> "emoji";
      default -> "misc";
    };
  }

  private static String extractExt(String fileName) {
    if (fileName == null) {
      return null;
    }
    int dot = fileName.lastIndexOf('.');
    if (dot < 0 || dot == fileName.length() - 1) {
      return null;
    }
    return fileName.substring(dot + 1).toLowerCase();
  }

  private static boolean isMediaType(int mediaType) {
    return mediaType == CommonProto.MsgType.MSG_TYPE_IMAGE_VALUE
      || mediaType == CommonProto.MsgType.MSG_TYPE_VOICE_VALUE
      || mediaType == CommonProto.MsgType.MSG_TYPE_VIDEO_VALUE
      || mediaType == CommonProto.MsgType.MSG_TYPE_FILE_VALUE
      || mediaType == CommonProto.MsgType.MSG_TYPE_EMOJI_VALUE;
  }

  private ImMessage buildUploadResp(ImMessage request, int code, String msg,
                                    String objectKey, String presignedUrl, long expireAt) {
    byte codecId = request.getCodecId();
    Object respBody = dualBody(codecId,
      () -> UploadProto.UploadResp.newBuilder()
        .setCode(code).setMessage(msg)
        .setObjectKey(objectKey).setPresignedUrl(presignedUrl).setExpireAt(expireAt).build(),
      () -> new JsonObject().put("code", code).put("message", msg)
        .put("objectKey", objectKey).put("presignedUrl", presignedUrl).put("expireAt", expireAt));
    return buildResponse(request, CMD_UPLOAD_RESP_VALUE, respBody);
  }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./mvnw -pl pomelo-logic-server -am test -Dtest=UploadServiceTest`

Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/service/UploadService.java \
        pomelo-logic-server/src/test/java/com/github/moxib/pomelo/logic/service/UploadServiceTest.java
git commit -m "feat: UploadService 签发 presigned PUT + 校验"
```

---

### Task 6: MediaUrlSigner 注入 4 个 service（8 处出站点）

**Files:**
- Modify: `pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/service/PullService.java`
- Modify: `pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/service/GroupPullService.java`
- Modify: `pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/service/C2CService.java`
- Modify: `pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/service/C2GService.java`
- Modify: `pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/LogicVerticle.java`
- Modify: `pomelo-logic-server/src/test/java/com/github/moxib/pomelo/logic/service/C2CServiceTest.java`
- Test: `pomelo-logic-server/src/test/java/com/github/moxib/pomelo/logic/service/PullServiceMediaSignTest.java`

**Interfaces:**
- Consumes: `MediaUrlSigner`（Task 4）、`MinioObjectPresigner`/`MinioMediaUrlSigner`（Task 3/4）。
- Produces: 4 个 service 构造函数新增最后一个参数 `MediaUrlSigner mediaUrlSigner`；`LogicVerticle` 组装 presigner+signer 并注入。

- [ ] **Step 1: 写失败测试 `PullServiceMediaSignTest`**

```java
package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.logic.infrastructure.MessageRepository;
import com.github.moxib.pomelo.logic.model.MessageRecord;
import com.github.moxib.pomelo.logic.model.UserIdInfo;
import com.github.moxib.pomelo.proto.common.CommonProto;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PullServiceMediaSignTest {

  private static final MessageRecord IMAGE = MessageRecord.builder()
    .id(1L).senderId(10L).recipientId(20L).conversationId("10:20")
    .msgType(2).content("{\"key\":\"image/10/x.jpg\"}")
    .seq(1L).status(0).createdAt(1L).build();

  private final MessageRepository stubRepo = new MessageRepository() {
    @Override public Future<Boolean> save(MessageRecord record) { return Future.succeededFuture(true); }
    @Override public Future<Void> updateStatus(long messageId, int newStatus) { return Future.succeededFuture(); }
    @Override public Future<Void> batchUpdateStatus(List<Long> ids, int newStatus) { return Future.succeededFuture(); }
    @Override public Future<List<MessageRecord>> pullPending(long recipientId, long sinceSeq, int limit) {
      return Future.succeededFuture(List.of(IMAGE));
    }
    @Override public Future<MessageRecord> findById(long messageId) { return Future.succeededFuture(null); }
    @Override public Future<List<MessageRecord>> findByIds(List<Long> ids) { return Future.succeededFuture(List.of()); }
    @Override public Future<List<MessageRecord>> pullConversation(String cid, long before, int limit) {
      return Future.succeededFuture(List.of());
    }
    @Override public Future<Map<Long, UserIdInfo>> findUserIdsByIds(List<Long> ids) {
      return Future.succeededFuture(Map.of());
    }
  };

  @Test
  void mediaContentGetsSignedUrlOnPull() throws Exception {
    MediaUrlSigner signer = (msgType, content) -> content + "?signed";
    PullService service = new PullService(stubRepo, signer);

    byte[] body = new JsonObject().put("userId", "20").put("seq", 0L).put("limit", 10)
      .toBuffer().getBytes();
    ImMessage req = ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER).version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId((byte) 1).cmd(CommonProto.Cmd.CMD_PULL_REQ_VALUE)
      .messageId("p-1").body(body).varHeaders(new HashMap<>()).build();

    ImMessage resp = service.process(req).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    JsonObject json = new JsonObject(new String(resp.getBody(), StandardCharsets.UTF_8));
    String content = json.getJsonArray("messages").getJsonObject(0).getString("content");
    assertTrue(content.endsWith("?signed"), "拉取消息 content 应被签名: " + content);
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -pl pomelo-logic-server -am test -Dtest=PullServiceMediaSignTest`

Expected: 编译失败（`PullService` 构造函数仍是单参数）。

- [ ] **Step 3: 注入 `MediaUrlSigner` 到 `PullService`**

构造函数改为：

```java
  public PullService(MessageRepository messageRepo, MediaUrlSigner mediaUrlSigner) {
    this.messageRepo = messageRepo;
    this.mediaUrlSigner = mediaUrlSigner;
    this.defaultPullLimit = ConfigHolder.getInt("message.pullLimit", 50);
  }
```

新增字段：

```java
  private final MediaUrlSigner mediaUrlSigner;
```

在 `buildPullResp` 的 PB 分支循环内，把：

```java
          CommonProto.MessageContent.Builder mc = CommonProto.MessageContent.newBuilder()
            .setMsgTypeValue(r.getMsgType())
            .setContent(ByteString.copyFromUtf8(r.getContent() != null ? r.getContent() : ""))
```

改为（先在循环里计算 `signedContent`，再引用）：

```java
          String signedContent = mediaUrlSigner.signContent(r.getMsgType(), r.getContent());
          CommonProto.MessageContent.Builder mc = CommonProto.MessageContent.newBuilder()
            .setMsgTypeValue(r.getMsgType())
            .setContent(ByteString.copyFromUtf8(signedContent != null ? signedContent : ""))
```

JSON 分支循环内，把：

```java
          msg.put("content", r.getContent() != null ? r.getContent() : "");
```

改为：

```java
          msg.put("content", mediaUrlSigner.signContent(r.getMsgType(), r.getContent()));
```

- [ ] **Step 4: 注入 `MediaUrlSigner` 到 `GroupPullService`**

构造函数改为：

```java
  public GroupPullService(GroupRepository groupRepo, MessageRepository messageRepo,
                          MediaUrlSigner mediaUrlSigner) {
    this.groupRepo = groupRepo;
    this.messageRepo = messageRepo;
    this.mediaUrlSigner = mediaUrlSigner;
  }
```

新增字段：

```java
  private final MediaUrlSigner mediaUrlSigner;
```

在 `buildPullRespWithMessages` 的 PB 循环内，把：

```java
        CommonProto.MessageContent.Builder mc = CommonProto.MessageContent.newBuilder()
          .setMsgTypeValue(m.getMsgType())
          .setContent(ByteString.copyFromUtf8(m.getContent() != null ? m.getContent() : ""))
```

改为：

```java
        String signedContent = mediaUrlSigner.signContent(m.getMsgType(), m.getContent());
        CommonProto.MessageContent.Builder mc = CommonProto.MessageContent.newBuilder()
          .setMsgTypeValue(m.getMsgType())
          .setContent(ByteString.copyFromUtf8(signedContent != null ? signedContent : ""))
```

JSON 循环内，把：

```java
        msg.put("content", m.getContent() != null ? m.getContent() : "");
```

改为：

```java
        msg.put("content", mediaUrlSigner.signContent(m.getMsgType(), m.getContent()));
```

- [ ] **Step 5: 注入 `MediaUrlSigner` 到 `C2CService`**

构造函数签名末尾加参数：

```java
  public C2CService(PushRouter pushRouter, MessageRepository messageRepo, SeqClientService seqClient,
                    SnowflakeIdGenerator snowflake, SessionRouteTable routeTable,
                    MediaUrlSigner mediaUrlSigner) {
    ...
    this.mediaUrlSigner = mediaUrlSigner;
  }
```

新增字段：

```java
  private final MediaUrlSigner mediaUrlSigner;
```

在 `publishC2CNotify` 的 `.onSuccess(recipientCodec -> {` 块内，`byte pushCodec;` 之后加：

```java
        String signedContent = mediaUrlSigner.signContent(record.getMsgType(), record.getContent());
```

把 PB 分支：

```java
            .setContent(ByteString.copyFromUtf8(record.getContent() != null ? record.getContent() : ""));
```

改为：

```java
            .setContent(ByteString.copyFromUtf8(signedContent != null ? signedContent : ""));
```

把 JSON 分支：

```java
          jsonMsgContent.put("content", record.getContent() != null ? record.getContent() : "");
```

改为：

```java
          jsonMsgContent.put("content", signedContent != null ? signedContent : "");
```

- [ ] **Step 6: 注入 `MediaUrlSigner` 到 `C2GService`**

构造函数签名末尾加参数：

```java
  public C2GService(Vertx vertx, PushRouter pushRouter, GroupRepository groupRepo,
                    SeqClientService seqClient, SnowflakeIdGenerator snowflake,
                    SessionRouteTable routeTable, MediaUrlSigner mediaUrlSigner) {
    ...
    this.mediaUrlSigner = mediaUrlSigner;
  }
```

新增字段：

```java
  private final MediaUrlSigner mediaUrlSigner;
```

在 `pushToGroupMembers` 方法开头（`groupRepo.findMembers(...).onSuccess(members -> {` 之后、`for` 之前）加：

```java
      String signedContent = mediaUrlSigner.signContent(ctx.getMsgType(), ctx.getContent());
```

把 PB 分支：

```java
                .setContent(ByteString.copyFromUtf8(ctx.getContent() != null ? ctx.getContent() : ""))
```

改为：

```java
                .setContent(ByteString.copyFromUtf8(signedContent != null ? signedContent : ""))
```

把 JSON 分支：

```java
              jsonMsg.put("content", ctx.getContent() != null ? ctx.getContent() : "");
```

改为：

```java
              jsonMsg.put("content", signedContent != null ? signedContent : "");
```

- [ ] **Step 7: `LogicVerticle` 组装 presigner + signer 并注入**

`LogicVerticle` 顶部 import 区加：

```java
import com.github.moxib.pomelo.logic.infrastructure.MinioObjectPresigner;
```

在 `start()` 中，`PushRouter pushRouter = new PushRouter(vertx);` 之后加：

```java
        var presigner = new MinioObjectPresigner();
        var mediaUrlSigner = new MinioMediaUrlSigner(presigner);
```

把 4 个 service 的构造改为（末尾加 `mediaUrlSigner`）：

```java
        c2cService = new C2CService(pushRouter, messageRepo, seqClient, snowflake, routeTable, mediaUrlSigner);
        pullService = new PullService(messageRepo, mediaUrlSigner);
        c2gService = new C2GService(vertx, pushRouter, groupRepo, seqClient, snowflake, routeTable, mediaUrlSigner);
        groupPullService = new GroupPullService(groupRepo, messageRepo, mediaUrlSigner);
```

（`MinioMediaUrlSigner` 在 `com.github.moxib.pomelo.logic.service`，已被 `import ...service.*` 通配覆盖，无需额外 import。）

- [ ] **Step 8: 更新 `C2CServiceTest` 传 pass-through signer**

把：

```java
    C2CService service = new C2CService(noopPush, stubRepo(), seqClient,
      new SnowflakeIdGenerator(1), new SessionRouteTable(vertx));
```

改为：

```java
    C2CService service = new C2CService(noopPush, stubRepo(), seqClient,
      new SnowflakeIdGenerator(1), new SessionRouteTable(vertx), (msgType, content) -> content);
```

- [ ] **Step 9: 运行确认通过（全量 logic-server 测试）**

Run: `./mvnw -pl pomelo-logic-server -am test`

Expected: PASS（含 `C2CServiceTest`、`PullServiceMediaSignTest` 及既有测试）。

- [ ] **Step 10: Commit**

```bash
git add pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/service/PullService.java \
        pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/service/GroupPullService.java \
        pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/service/C2CService.java \
        pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/service/C2GService.java \
        pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/LogicVerticle.java \
        pomelo-logic-server/src/test/java/com/github/moxib/pomelo/logic/service/C2CServiceTest.java \
        pomelo-logic-server/src/test/java/com/github/moxib/pomelo/logic/service/PullServiceMediaSignTest.java
git commit -m "feat: 读侧 MediaUrlSigner 注入 4 个 service（8 处出站点）"
```

---

### Task 7: 网关路由 + LogicVerticle 上传接入

**Files:**
- Modify: `pomelo-gateway/src/main/java/com/github/moxib/pomelo/gateway/handler/MessageDispatcher.java`
- Modify: `pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/LogicVerticle.java`

**Interfaces:**
- Consumes: `UploadService`（Task 5）、`MinioObjectPresigner`（Task 3，已在 LogicVerticle 中）。

- [ ] **Step 1: `MessageDispatcher.cmdToAddress` 加上传路由**

在 `cmdToAddress` 方法中，`if (cmd == CMD_PULL_REQ_VALUE)` 那行之前加：

```java
    if (cmd == CMD_UPLOAD_REQ_VALUE)       return "logic.upload";
```

- [ ] **Step 2: `LogicVerticle` 实例化 `UploadService` + 注册 consumer**

在 `LogicVerticle` 字段区加：

```java
  private UploadService uploadService;
```

在 `start()` 里（`var mediaUrlSigner = new MinioMediaUrlSigner(presigner);` 之后）加：

```java
        uploadService = new UploadService(presigner, snowflake);
```

在 `bus.consumer("logic.pull", ...)` 那行附近加：

```java
        bus.consumer("logic.upload", (Message<Buffer> msg) -> dispatch(msg, uploadService::process));
```

- [ ] **Step 3: 全量编译 + 测试**

Run: `./mvnw clean test`

Expected: BUILD SUCCESS，所有模块测试通过。

- [ ] **Step 4: Commit**

```bash
git add pomelo-gateway/src/main/java/com/github/moxib/pomelo/gateway/handler/MessageDispatcher.java \
        pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/LogicVerticle.java
git commit -m "feat: 网关路由 CMD_UPLOAD_REQ → logic.upload，LogicVerticle 接入 UploadService"
```

---

## 手动端到端验证清单（无自动化）

自动化覆盖：presigner 集成（PUT/GET）、signer 单测、UploadService 校验、PullService 签名注入。以下为可选的本地人工验证（需 docker-compose 起 MinIO + PG + seqsvr）：

1. `docker compose up -d silo postgres redis seqsvr-mediate`，确认 `silo` 控制台 9003 可访问。
2. 用 JS SDK 发 `CMD_UPLOAD_REQ{mediaType:2,fileName:"a.jpg",size:N}`，拿 `objectKey` + `presigned_url`。
3. `curl -X PUT --upload-file a.jpg "<presigned_url>"`，确认 MinIO 桶内出现对象。
4. 发 `CMD_C2C_REQ{msgType:2, content:"{\"key\":\"<objectKey>\"}"}`。
5. 对端收 notify 后，用 `content.url` 直接 GET 图片，确认可读；确认 DB `im_message_c2c.content` 只存 key（无 url 字段）。
6. 重新 `PULL`，确认返回的 `content.url` 是新签的可用链接。
