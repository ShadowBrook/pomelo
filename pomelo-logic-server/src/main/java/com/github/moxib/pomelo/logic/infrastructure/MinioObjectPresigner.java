package com.github.moxib.pomelo.logic.infrastructure;

import com.github.moxib.pomelo.config.ConfigHolder;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http.Method;
import io.minio.MinioClient;

import java.time.Duration;

/**
 * MinIO / S3 兼容对象存储的预签名实现。
 * presign 为本地 HMAC 运算，无网络往返；通过官方 MinIO SDK（io.minio）生成。
 */
public class MinioObjectPresigner implements ObjectPresigner {

  private final MinioClient client;
  private final String bucket;
  private final Duration putTtl;
  private final Duration getTtl;

  public MinioObjectPresigner() {
    this(ConfigHolder.getString("media.endpoint", "http://silo:9000"),
         ConfigHolder.getString("media.publicEndpoint", ""),
         ConfigHolder.getString("media.bucket", "pomelo-media"),
         ConfigHolder.getString("media.accessKey", "pomelo-admin"),
         ConfigHolder.getString("media.secretKey", "pomelo-admin-password"),
         ConfigHolder.getInt("media.putUrlTtlSeconds", 300),
         ConfigHolder.getInt("media.getUrlTtlSeconds", 604800));
  }

  public MinioObjectPresigner(String endpoint, String bucket, String accessKey, String secretKey,
                              int putTtlSeconds, int getTtlSeconds) {
    this(endpoint, endpoint, bucket, accessKey, secretKey, putTtlSeconds, getTtlSeconds);
  }

  public MinioObjectPresigner(String endpoint, String publicEndpoint, String bucket,
                              String accessKey, String secretKey,
                              int putTtlSeconds, int getTtlSeconds) {
    this.bucket = bucket;
    this.putTtl = Duration.ofSeconds(putTtlSeconds);
    this.getTtl = Duration.ofSeconds(getTtlSeconds);
    // 显式指定 region：MinIO SDK 在 region 未设置时会向 endpoint 发起网络请求查询 bucket 区域，
    // 而 publicEndpoint（浏览器可达地址）在服务端容器内可能不可达。设了 region 后 presign 纯本地 HMAC。
    // MinIO 默认 region 即 us-east-1。
    String signEndpoint = (publicEndpoint == null || publicEndpoint.isBlank()) ? endpoint : publicEndpoint;
    this.client = MinioClient.builder()
      .endpoint(signEndpoint)
      .region("us-east-1")
      .credentials(accessKey, secretKey)
      .build();
  }

  @Override
  public String presignPut(String objectKey, String contentType) {
    // MinIO SDK 预签名不把 content-type 计入签名头；保留参数以符合接口契约和业务校验语义
    try {
      return client.getPresignedObjectUrl(
        GetPresignedObjectUrlArgs.builder()
          .method(Method.PUT)
          .bucket(bucket)
          .object(objectKey)
          .expiry(boundedSeconds(putTtl))
          .build());
    } catch (Exception e) {
      throw new IllegalStateException("生成预签名 PUT URL 失败: " + objectKey, e);
    }
  }

  @Override
  public String presignGet(String objectKey) {
    try {
      return client.getPresignedObjectUrl(
        GetPresignedObjectUrlArgs.builder()
          .method(Method.GET)
          .bucket(bucket)
          .object(objectKey)
          .expiry(boundedSeconds(getTtl))
          .build());
    } catch (Exception e) {
      throw new IllegalStateException("生成预签名 GET URL 失败: " + objectKey, e);
    }
  }

  private static int boundedSeconds(Duration ttl) {
    return (int) Math.min(ttl.toSeconds(), 604800L);
  }
}