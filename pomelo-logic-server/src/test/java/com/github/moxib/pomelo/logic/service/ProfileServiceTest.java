package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.proto.common.CommonProto;
import com.github.moxib.pomelo.proto.profile.ProfileProto;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PreparedQuery;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 头像更新服务的校验边界与写入参数测试。
 * im_user.avatar 只接受本人上传、服务端签发形态的对象 key；读取出口经签名器换 presigned GET。
 * 不做变更扇出推送：新鲜度由客户端展示点拉取保证。
 */
@DisplayName("ProfileService 头像更新测试")
class ProfileServiceTest {

  private Vertx vertx;
  private RecordingPool pool;

  private static final long USER = 100L;
  private static final long OTHER = 200L;

  @BeforeEach
  void setUp() {
    vertx = Vertx.vertx();
    pool = new RecordingPool();
  }

  @AfterEach
  void tearDown() {
    if (vertx == null) {
      return;
    }
    CountDownLatch latch = new CountDownLatch(1);
    vertx.close().onComplete(ar -> latch.countDown());
    try {
      latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private ProfileService service() {
    return new ProfileService(pool.proxy(), new MediaUrlSigner() {
      @Override
      public String signContent(int msgType, String content) {
        return content;
      }

      @Override
      public String signAvatar(String avatar) {
        return avatar == null || avatar.isEmpty() ? "" : "signed:" + avatar;
      }
    });
  }

  private static ImMessage updateReq(String avatar) {
    Map<String, String> headers = new HashMap<>();
    headers.put("userId", String.valueOf(USER));
    return ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId((byte) 0)
      .cmd(CommonProto.Cmd.CMD_PROFILE_UPDATE_REQ_VALUE)
      .messageId("m-1")
      .body(ProfileProto.ProfileUpdateReq.newBuilder().setAvatar(avatar).build().toByteArray())
      .varHeaders(headers)
      .build();
  }

  private static ImMessage signatureReq(String signature) {
    Map<String, String> headers = new HashMap<>();
    headers.put("userId", String.valueOf(USER));
    return ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId((byte) 0)
      .cmd(CommonProto.Cmd.CMD_PROFILE_UPDATE_REQ_VALUE)
      .messageId("m-1")
      .body(ProfileProto.ProfileUpdateReq.newBuilder().setSignature(signature).build().toByteArray())
      .varHeaders(headers)
      .build();
  }

  private static ImMessage awaitResult(ProfileService service, ImMessage req) throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<ImMessage> resp = new AtomicReference<>();
    AtomicReference<Throwable> err = new AtomicReference<>();
    service.process(req).onComplete(ar -> {
      if (ar.succeeded()) {
        resp.set(ar.result());
      } else {
        err.set(ar.cause());
      }
      done.countDown();
    });
    assertTrue(done.await(10, TimeUnit.SECONDS), "process 应完成");
    assertNull(err.get(), "process 失败: " + err.get());
    return resp.get();
  }

  private static CommonProto.ErrorBody asError(ImMessage resp)
    throws com.google.protobuf.InvalidProtocolBufferException {
    return CommonProto.ErrorBody.parseFrom(resp.getBody());
  }

  @Test
  @DisplayName("合法对象 key 写库且响应返回签名 URL")
  void validKeyIsSavedAndSignedBack() throws Exception {
    String key = "image/" + USER + "/20260919/" + "a".repeat(32) + ".png";

    ImMessage resp = awaitResult(service(), updateReq(key));

    assertEquals(1, pool.sqls.size(), "应执行一条更新语句");
    assertTrue(pool.sqls.get(0).contains("UPDATE im_user"), "应为头像更新语句: " + pool.sqls.get(0));
    Tuple params = pool.params.get(0);
    assertEquals(key, params.getString(0), "avatar 参数应为对象 key");
    assertEquals(USER, params.getLong(2), "更新对象必须是认证身份");

    ProfileProto.ProfileUpdateResp body = ProfileProto.ProfileUpdateResp.parseFrom(resp.getBody());
    assertEquals(0, body.getCode());
    assertEquals("signed:" + key, body.getAvatar(), "响应应携带签名后的头像 URL");
  }

  @Test
  @DisplayName("空 avatar 表示清除头像，写空串")
  void emptyAvatarClearsColumn() throws Exception {
    ImMessage resp = awaitResult(service(), updateReq(""));

    assertEquals(1, pool.sqls.size());
    assertEquals("", pool.params.get(0).getString(0), "清除应写空串");
    ProfileProto.ProfileUpdateResp body = ProfileProto.ProfileUpdateResp.parseFrom(resp.getBody());
    assertEquals(0, body.getCode());
  }

  @Test
  @DisplayName("他人上传的对象 key 被拒绝且不触库")
  void foreignKeyIsRejected() throws Exception {
    String key = "image/" + OTHER + "/20260919/" + "a".repeat(32) + ".jpg";

    ImMessage resp = awaitResult(service(), updateReq(key));

    assertEquals(ErrorCode.BAD_REQUEST.getCode(), asError(resp).getCode());
    assertTrue(pool.sqls.isEmpty(), "校验失败不应触达数据库");
  }

  @Test
  @DisplayName("非对象 key 形态（外部 URL）被拒绝")
  void externalUrlIsRejected() throws Exception {
    ImMessage resp = awaitResult(service(), updateReq("https://evil.example.com/avatar.png"));

    assertEquals(ErrorCode.BAD_REQUEST.getCode(), asError(resp).getCode());
    assertTrue(pool.sqls.isEmpty(), "校验失败不应触达数据库");
  }

  @Test
  @DisplayName("缺少认证身份头被拒绝且不触库")
  void missingAuthHeaderIsRejected() throws Exception {
    Map<String, String> headers = new HashMap<>();
    ImMessage req = ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId((byte) 0)
      .cmd(CommonProto.Cmd.CMD_PROFILE_UPDATE_REQ_VALUE)
      .messageId("m-1")
      .body(ProfileProto.ProfileUpdateReq.newBuilder().setAvatar("").build().toByteArray())
      .varHeaders(headers)
      .build();

    ImMessage resp = awaitResult(service(), req);

    assertEquals(ErrorCode.UNAUTHORIZED.getCode(), asError(resp).getCode());
    assertTrue(pool.sqls.isEmpty(), "未认证请求不应触达数据库");
  }

  @Test
  @DisplayName("仅更新签名：写 signature 列且不触碰 avatar")
  void signatureOnlyUpdate() throws Exception {
    ImMessage resp = awaitResult(service(), signatureReq("自由职业者"));

    assertFalse(pool.sqls.isEmpty());
    assertTrue(pool.sqls.get(0).contains("signature"), "应为签名更新语句: " + pool.sqls.get(0));
    assertFalse(pool.sqls.get(0).contains("avatar"), "签名更新不应触碰 avatar 列");
    assertEquals("自由职业者", pool.params.get(0).getString(0));

    ProfileProto.ProfileUpdateResp body = ProfileProto.ProfileUpdateResp.parseFrom(resp.getBody());
    assertEquals(0, body.getCode());
    assertEquals("自由职业者", body.getSignature(), "响应应回显新签名");
  }

  @Test
  @DisplayName("签名超过 128 字符被拒绝且不触库")
  void overlyLongSignatureIsRejected() throws Exception {
    ImMessage resp = awaitResult(service(), signatureReq("长".repeat(129)));

    assertEquals(ErrorCode.BAD_REQUEST.getCode(), asError(resp).getCode());
    assertTrue(pool.sqls.isEmpty(), "校验失败不应触达数据库");
  }

  @Test
  @DisplayName("avatar 与 signature 均未提供时拒绝")
  void emptyRequestIsRejected() throws Exception {
    Map<String, String> headers = new HashMap<>();
    headers.put("userId", String.valueOf(USER));
    ImMessage req = ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId((byte) 0)
      .cmd(CommonProto.Cmd.CMD_PROFILE_UPDATE_REQ_VALUE)
      .messageId("m-1")
      .body(ProfileProto.ProfileUpdateReq.newBuilder().build().toByteArray())
      .varHeaders(headers)
      .build();

    ImMessage resp = awaitResult(service(), req);

    assertEquals(ErrorCode.BAD_REQUEST.getCode(), asError(resp).getCode());
    assertTrue(pool.sqls.isEmpty(), "无可更新字段不应触达数据库");
  }

  @Test
  @DisplayName("用户不存在（更新 0 行）返回 NOT_FOUND")
  void unknownUserReturnsNotFound() throws Exception {
    pool.rowCount.set(0);
    String key = "image/" + USER + "/20260919/" + "a".repeat(32) + ".jpg";

    ImMessage resp = awaitResult(service(), updateReq(key));

    assertEquals(ErrorCode.NOT_FOUND.getCode(), asError(resp).getCode());
  }

  /**
   * Pool 测试替身：记录 SQL 与参数，返回预设 rowCount 与空结果集。
   * 未预期的方法直接抛 UnsupportedOperationException，避免测试掩盖实现新增的调用。
   */
  private static final class RecordingPool {

    final List<String> sqls = new ArrayList<>();
    final List<Tuple> params = new ArrayList<>();
    final AtomicInteger rowCount = new AtomicInteger(1);

    Pool proxy() {
      return (Pool) Proxy.newProxyInstance(Pool.class.getClassLoader(), new Class<?>[]{Pool.class},
        (proxy, method, args) -> {
          if ("preparedQuery".equals(method.getName())) {
            return preparedQuery((String) args[0]);
          }
          if ("close".equals(method.getName())) {
            return Future.succeededFuture();
          }
          throw new UnsupportedOperationException("未预期的 Pool 调用: " + method.getName());
        });
    }

    @SuppressWarnings("unchecked")
    private PreparedQuery<RowSet<Row>> preparedQuery(String sql) {
      return (PreparedQuery<RowSet<Row>>) Proxy.newProxyInstance(PreparedQuery.class.getClassLoader(),
        new Class<?>[]{PreparedQuery.class}, (proxy, method, args) -> {
          if ("execute".equals(method.getName())) {
            sqls.add(sql);
            params.add((Tuple) args[0]);
            return Future.succeededFuture(rowSet());
          }
          throw new UnsupportedOperationException("未预期的 PreparedQuery 调用: " + method.getName());
        });
    }

    @SuppressWarnings("unchecked")
    private RowSet<Row> rowSet() {
      return (RowSet<Row>) Proxy.newProxyInstance(RowSet.class.getClassLoader(),
        new Class<?>[]{RowSet.class}, (proxy, method, args) -> switch (method.getName()) {
          case "rowCount" -> rowCount.get();
          case "size" -> 0;
          case "iterator" -> Collections.<Row>emptyList().iterator();
          default -> throw new UnsupportedOperationException("未预期的 RowSet 调用: " + method.getName());
        });
    }
  }
}
