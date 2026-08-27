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

    // SigV4: presigned PUT 把 content-type 计入签名头，PUT 必须原样带上该头，否则 MinIO 返回 400
    int putStatus = http.send(HttpRequest.newBuilder(URI.create(putUrl))
      .header("Content-Type", "text/plain")
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
