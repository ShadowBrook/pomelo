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