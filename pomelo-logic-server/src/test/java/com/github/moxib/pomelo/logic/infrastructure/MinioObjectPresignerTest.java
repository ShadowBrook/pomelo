package com.github.moxib.pomelo.logic.infrastructure;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.testcontainers.junit.jupiter.Testcontainers;

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

  private void createBucket(String endpoint, String bucket) throws Exception {
    MinioClient client = MinioClient.builder()
      .endpoint(endpoint)
      .credentials(ACCESS_KEY, SECRET_KEY)
      .build();
    // 幂等建桶：共享容器下重复 makeBucket 会报 BucketAlreadyOwnedByYou
    if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
      client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
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

    // MinIO SDK 预签名 PUT 不把 content-type 计入签名头，带上该头不影响签名校验，PUT 应 200
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

  @Test
  void presignedPutWithoutContentTypeRoundTrips() throws Exception {
    String endpoint = endpoint();
    String bucket = "test-bucket-no-ct";
    createBucket(endpoint, bucket);
    MinioObjectPresigner presigner = new MinioObjectPresigner(endpoint, bucket, ACCESS_KEY, SECRET_KEY, 300, 600);

    String putUrl = presigner.presignPut("a/c.txt", null);
    HttpClient http = HttpClient.newHttpClient();
    int putStatus = http.send(HttpRequest.newBuilder(URI.create(putUrl))
      .PUT(HttpRequest.BodyPublishers.ofString("no-ct")).build(),
      HttpResponse.BodyHandlers.ofString()).statusCode();
    assertEquals(200, putStatus, "无 content-type 的 PUT 应成功");
  }
}
