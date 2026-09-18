package com.github.moxib.pomelo.logic.service;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LiveKit 凭证签发与 webhook 验签。
 * claims 形态须与官方 Kotlin SDK AccessToken.toJwt() 一致：
 * iss/sub/nbf/exp + video{roomJoin, room, canPublish, canSubscribe}。
 */
@DisplayName("LiveKit token 签发")
class LiveKitTokenServiceTest {

  private static final String SECRET = "test-secret-not-for-deployment";
  private static final String API_KEY = "devkey";

  private static String decodePayload(String jwt) {
    return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
  }

  private static boolean verifySignature(String jwt, String secret) throws Exception {
    String[] parts = jwt.split("\\.");
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] expected = mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
    return MessageDigest.isEqual(expected, Base64.getUrlDecoder().decode(parts[2]));
  }

  @Test
  @DisplayName("参与者 token：claims 逐项符合 LiveKit 约定")
  void issueProducesLiveKitCompatibleClaims() throws Exception {
    LiveKitTokenService service = new LiveKitTokenService(API_KEY, SECRET, 900, "ws://lk:7880");
    String jwt = service.issue(100L, "call-1-abc");

    assertTrue(verifySignature(jwt, SECRET), "签名须可用同一 secret 校验");
    JsonObject payload = new JsonObject(decodePayload(jwt));
    assertEquals(API_KEY, payload.getString("iss"));
    assertEquals("100", payload.getString("sub"));
    JsonObject video = payload.getJsonObject("video");
    assertNotNull(video);
    assertEquals(true, video.getBoolean("roomJoin"));
    assertEquals("call-1-abc", video.getString("room"));
    assertEquals(true, video.getBoolean("canPublish"));
    assertEquals(true, video.getBoolean("canSubscribe"));
    long nbf = payload.getLong("nbf");
    long exp = payload.getLong("exp");
    assertEquals(900, exp - nbf - 10, "TTL 900s（nbf 提前 10s 时钟偏移）");
  }

  @Test
  @DisplayName("篡改 payload 后签名校验失败")
  void tamperedPayloadFailsSignature() throws Exception {
    LiveKitTokenService service = new LiveKitTokenService(API_KEY, SECRET, 900, "ws://lk:7880");
    String jwt = service.issue(100L, "call-1-abc");
    String[] parts = jwt.split("\\.");
    String forgedPayload = LiveKitTokenService.base64Url(
      new JsonObject(decodePayload(jwt)).put("sub", "999").encode());
    String forged = parts[0] + "." + forgedPayload + "." + parts[2];

    assertFalse(verifySignature(forged, SECRET));
  }

  @Test
  @DisplayName("内置开发密钥默认拒绝签发（与 JWT 护栏同策略）")
  void refusesDefaultSecret() {
    LiveKitTokenService service = new LiveKitTokenService(
      API_KEY, LiveKitTokenService.DEV_DEFAULT_SECRET, 900, "ws://lk:7880");
    assertThrows(IllegalStateException.class, () -> service.issue(100L, "call-1-abc"));
  }

  @Test
  @DisplayName("webhook：合法签名且 body 哈希一致 → 通过")
  void webhookVerificationPasses() {
    LiveKitTokenService service = new LiveKitTokenService(API_KEY, SECRET, 900, "ws://lk:7880");
    String body = "{\"event\":\"participant_left\",\"room\":{\"name\":\"call-1-abc\"}}";

    // 模拟 LiveKit 侧构造：JWT payload 带 body 的 sha256
    long now = System.currentTimeMillis() / 1000;
    JsonObject claims = new JsonObject()
      .put("iss", API_KEY)
      .put("exp", now + 300)
      .put("sha256", LiveKitTokenService.sha256Hex(body));
    String header = LiveKitTokenService.base64Url(new JsonObject().put("alg", "HS256").put("typ", "JWT").encode());
    String payloadB64 = LiveKitTokenService.base64Url(claims.encode());
    String signingInput = header + "." + payloadB64;
    String token = signingInput + "." + LiveKitTokenService.base64Url(
      LiveKitTokenService.hmacSha256(SECRET, signingInput));

    JsonObject payload = service.verifyWebhook(body, "Bearer " + token);
    assertNotNull(payload, "验签通过应返回 JWT payload");
    assertEquals(LiveKitTokenService.sha256Hex(body), payload.getString("sha256"),
      "payload 应携带 body 哈希（event/room 由调用方解析 body 获得）");
  }

  @Test
  @DisplayName("webhook：body 被篡改 → 拒绝")
  void webhookRejectsTamperedBody() {
    LiveKitTokenService service = new LiveKitTokenService(API_KEY, SECRET, 900, "ws://lk:7880");
    String body = "{\"event\":\"participant_left\"}";
    String signedBody = "{\"event\":\"participant_left\",\"victim\":true}";
    String jwt = webhookToken(SECRET, signedBody);

    assertNull(service.verifyWebhook(body, "Bearer " + jwt), "换 body 复用旧签名必须被拒");
  }

  @Test
  @DisplayName("webhook：错误 secret → 拒绝")
  void webhookRejectsWrongSecret() {
    LiveKitTokenService service = new LiveKitTokenService(API_KEY, SECRET, 900, "ws://lk:7880");
    String body = "{\"event\":\"participant_left\"}";
    String jwt = webhookToken("attacker-secret", body);

    assertNull(service.verifyWebhook(body, "Bearer " + jwt));
  }

  @Test
  @DisplayName("webhook：Authorization 头缺失或格式错 → 拒绝")
  void webhookRejectsMalformedHeader() {
    LiveKitTokenService service = new LiveKitTokenService(API_KEY, SECRET, 900, "ws://lk:7880");
    assertNull(service.verifyWebhook("{}", null));
    assertNull(service.verifyWebhook("{}", "Bearer not-a-jwt"));
    assertNull(service.verifyWebhook("{}", "not-bearer"));
  }

  private static String webhookToken(String secret, String body) {
    long now = System.currentTimeMillis() / 1000;
    JsonObject claims = new JsonObject()
      .put("iss", API_KEY)
      .put("exp", now + 300)
      .put("sha256", LiveKitTokenService.sha256Hex(body));
    String header = LiveKitTokenService.base64Url(new JsonObject().put("alg", "HS256").put("typ", "JWT").encode());
    String payloadB64 = LiveKitTokenService.base64Url(claims.encode());
    String signingInput = header + "." + payloadB64;
    return signingInput + "." + LiveKitTokenService.base64Url(
      LiveKitTokenService.hmacSha256(secret, signingInput));
  }
}
