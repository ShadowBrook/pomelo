package com.github.moxib.pomelo.logic.infrastructure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归测试：显式设置 region 后，presign 是纯本地 HMAC 运算，不向 endpoint 发起网络请求。
 * 若 SDK 未设 region 而去做 bucket 区域查询，对不可达 endpoint 会抛 ConnectException。
 */
class MinioPresignerLocalTest {

  // endpoint 故意设为不可达端口；成功生成签名 URL 即证明无网络依赖
  private static final String DEAD_ENDPOINT = "http://127.0.0.1:9";

  @Test
  void presignPutIsLocalWhenRegionSet() {
    MinioObjectPresigner presigner = new MinioObjectPresigner(
      DEAD_ENDPOINT, DEAD_ENDPOINT, "bucket", "ak", "sk", 300, 600);
    String url = presigner.presignPut("a/b.txt", "text/plain");
    assertTrue(url.startsWith(DEAD_ENDPOINT + "/"));
    assertTrue(url.contains("X-Amz-Signature="), "应生成带签名的 URL: " + url);
  }

  @Test
  void presignGetIsLocalWhenRegionSet() {
    MinioObjectPresigner presigner = new MinioObjectPresigner(
      DEAD_ENDPOINT, DEAD_ENDPOINT, "bucket", "ak", "sk", 300, 600);
    String url = presigner.presignGet("a/b.txt");
    assertTrue(url.contains("X-Amz-Signature="), "应生成带签名的 URL: " + url);
  }
}
