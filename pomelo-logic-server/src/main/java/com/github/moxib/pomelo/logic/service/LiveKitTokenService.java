package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.config.ConfigHolder;
import io.vertx.core.json.JsonObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * LiveKit 入会凭证签发（手写 HS256 JWT）。
 * <p>
 * 刻意不引 livekit-server SDK：它传递 protobuf 4.29/jackson 2.22，与本项目钉住的
 * protobuf 4.31/jackson 2.21 冲突。而通话需要的凭证就是普通 JWT——claims 与官方
 * Kotlin SDK {@code AccessToken.toJwt()} 产物一致：
 * {@code iss=apiKey, sub=identity, nbf/exp, video={roomJoin, room, canPublish, canSubscribe}}。
 * <p>
 * 房间管理（RoomService）走 Twirp JSON，见 {@link LiveKitRoomClient}，
 * 服务调用凭证用 {@link #issueServiceToken()}。
 */
public class LiveKitTokenService implements CallTokenIssuer {

  /** 仓库内置开发密钥；不可用于签发真实凭证 */
  public static final String DEV_DEFAULT_SECRET = "pomelo-livekit-dev-secret-change-in-production";

  private static final long SKEW_SECONDS = 10;

  private final String apiKey;
  private final String secret;
  private final long ttlSeconds;
  private final String wsUrl;
  private final boolean usingDefaultSecret;

  public LiveKitTokenService() {
    this.apiKey = ConfigHolder.getString("livekit.apiKey", "devkey");
    this.secret = ConfigHolder.getString("livekit.secret", DEV_DEFAULT_SECRET);
    this.ttlSeconds = ConfigHolder.getInt("livekit.tokenTtlSeconds", 900);
    this.wsUrl = ConfigHolder.getString("livekit.publicUrl", "ws://localhost:7880");
    this.usingDefaultSecret = secret == null || secret.isBlank() || DEV_DEFAULT_SECRET.equals(secret);
    if (usingDefaultSecret) {
      // 不在构造期 fail-fast（与 jwt 护栏不同：通话不可用不危及既有链路），
      // 但签发时会拒绝——见 issue()
      org.slf4j.LoggerFactory.getLogger(LiveKitTokenService.class)
        .warn("livekit.secret 缺失或为仓库内置开发密钥，通话签发将被拒绝；请注入 POMELO_LIVEKIT_SECRET");
    }
  }

  /** 供测试注入 */
  LiveKitTokenService(String apiKey, String secret, long ttlSeconds, String wsUrl) {
    this.apiKey = apiKey;
    this.secret = secret;
    this.ttlSeconds = ttlSeconds;
    this.wsUrl = wsUrl;
    this.usingDefaultSecret = DEV_DEFAULT_SECRET.equals(secret);
  }

  @Override
  public String wsUrl() {
    return wsUrl;
  }

  /** 参与者入会 token：可发布可订阅，仅限单房间 */
  @Override
  public String issue(long userId, String room) {
    requireUsableSecret();
    long now = System.currentTimeMillis() / 1000;
    JsonObject video = new JsonObject()
      .put("roomJoin", true)
      .put("room", room)
      .put("canPublish", true)
      .put("canSubscribe", true);
    JsonObject claims = new JsonObject()
      .put("iss", apiKey)
      .put("sub", String.valueOf(userId))
      .put("nbf", now - SKEW_SECONDS)
      .put("exp", now + ttlSeconds)
      .put("video", video);
    return signJwt(claims);
  }

  /**
   * RoomService 管理调用凭证（CreateRoom/DeleteRoom）。
   * grants 用 roomCreate/roomDestroy/roomList，TTL 60s 且不绑定具体房间。
   */
  public String issueServiceToken() {
    requireUsableSecret();
    long now = System.currentTimeMillis() / 1000;
    JsonObject video = new JsonObject()
      .put("roomCreate", true)
      .put("roomDestroy", true)
      .put("roomList", true);
    JsonObject claims = new JsonObject()
      .put("iss", apiKey)
      .put("sub", "pomelo-logic")
      .put("nbf", now - SKEW_SECONDS)
      .put("exp", now + 60)
      .put("video", video);
    return signJwt(claims);
  }

  private void requireUsableSecret() {
    if (usingDefaultSecret && !ConfigHolder.getBoolean("livekit.allowDefaultSecret", false)) {
      throw new IllegalStateException(
        "livekit.secret 缺失或仍是仓库内置开发密钥，拒绝签发通话凭证：请注入 POMELO_LIVEKIT_SECRET；"
          + "仅本地开发可设置 livekit.allowDefaultSecret=true");
    }
  }

  private String signJwt(JsonObject claims) {
    String header = base64Url(new JsonObject().put("alg", "HS256").put("typ", "JWT").encode());
    String payload = base64Url(claims.encode());
    String signingInput = header + "." + payload;
    return signingInput + "." + base64Url(hmacSha256(secret, signingInput));
  }

  /**
   * 校验 LiveKit webhook 请求：Authorization 头是以 API secret 签名的 JWT，
   * payload 带 body 的 sha256。返回 payload；验签失败返回 null。
   * <p>
   * 两处与 livekit-server 实际行为对齐（此前都写错，线上 webhook 全部验签失败）：
   * <ul>
   *   <li>Authorization 头是**裸 token**，livekit-server 不加 {@code "Bearer "} 前缀
   *       （两种形态都接受，便于代理/客户端保持惯例写法）；</li>
   *   <li>sha256 声明是摘要的**标准 base64**（notifier.go:
   *       {@code base64.StdEncoding.EncodeToString}），不是十六进制。</li>
   * </ul>
   * 依据：livekit-server v1.13.6 真实抓包（见 CallServiceTest 的 golden 用例）。
   */
  public JsonObject verifyWebhook(String rawBody, String authHeader) {
    JsonObject payload = verifyWebhookSignature(rawBody, authHeader);
    if (payload == null) {
      return null;
    }
    // exp 校验（LiveKit webhook token 有效期短）
    long exp = payload.getLong("exp", 0L);
    if (exp > 0 && exp < System.currentTimeMillis() / 1000 - SKEW_SECONDS) {
      return null;
    }
    return payload;
  }

  /** 签名 + body 摘要校验（不含时效），断言真实抓包报文时可单独复用 */
  JsonObject verifyWebhookSignature(String rawBody, String authHeader) {
    if (rawBody == null || authHeader == null) {
      return null;
    }
    String token = authHeader.startsWith("Bearer ")
      ? authHeader.substring("Bearer ".length()).trim()
      : authHeader.trim();
    String[] parts = token.split("\\.");
    if (parts.length != 3) {
      return null;
    }
    byte[] expected = hmacSha256(secret, parts[0] + "." + parts[1]);
    byte[] actual;
    try {
      actual = Base64.getUrlDecoder().decode(parts[2]);
    } catch (IllegalArgumentException e) {
      return null;
    }
    if (!MessageDigest.isEqual(expected, actual)) {
      return null;
    }
    try {
      JsonObject payload = new JsonObject(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
      String claimedHash = payload.getString("sha256");
      if (claimedHash == null
        || (!claimedHash.equals(sha256Base64(rawBody)) && !claimedHash.equals(sha256Hex(rawBody)))) {
        return null;
      }
      return payload;
    } catch (Exception e) {
      return null;
    }
  }

  static byte[] hmacSha256(String secret, String data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException("HMAC-SHA256 不可用", e);
    }
  }

  /** livekit-server webhook 的 sha256 声明编码：摘要的标准 base64 */
  static String sha256Base64(String data) {
    return Base64.getEncoder().encodeToString(sha256(data));
  }

  static String sha256Hex(String data) {
    byte[] digest = sha256(data);
    StringBuilder sb = new StringBuilder(digest.length * 2);
    for (byte b : digest) {
      sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
    }
    return sb.toString();
  }

  private static byte[] sha256(String data) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 不可用", e);
    }
  }

  static String base64Url(String data) {
    return base64Url(data.getBytes(StandardCharsets.UTF_8));
  }

  static String base64Url(byte[] data) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
  }
}
