package com.github.moxib.pomelo.config;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.PubSecKeyOptions;
import io.vertx.ext.auth.authentication.TokenCredentials;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * JWT 验签与解析（HS256，密钥来自 {@code jwt.secret}）。
 * gateway 与 logic 共用同一实现，避免各自维护 JWTAuth 配置。
 * 仅负责签名验证与 claims 解析/签发；黑名单判定在 logic 侧（TokenService）。
 */
public class JwtTokenParser {

  private static final Logger LOG = LoggerFactory.getLogger(JwtTokenParser.class);

  /** 仓库内置开发密钥；不可用于对外部署 */
  public static final String DEV_DEFAULT_SECRET = "pomelo-dev-secret-change-in-production";

  private final JWTAuth jwtAuth;
  private final int tokenTtlSeconds;

  public JwtTokenParser(Vertx vertx) {
    this.tokenTtlSeconds = ConfigHolder.getInt("jwt.ttlSeconds", 86400);
    String secret = ConfigHolder.getString("jwt.secret", DEV_DEFAULT_SECRET);
    requireUsableSecret(secret);
    this.jwtAuth = JWTAuth.create(vertx, new JWTAuthOptions()
      .addPubSecKey(new PubSecKeyOptions().setAlgorithm("HS256").setBuffer(secret)));
  }

  /**
   * 拒绝用仓库内置密钥启动。HS256 是对称算法，密钥即签发权——任何拿到仓库的人
   * 都能为任意 userId 签发合法 token，完成完整账户冒充。
   * <p>
   * 生产必须注入独立密钥（{@code POMELO_JWT_SECRET} 或配置 {@code jwt.secret}）；
   * 只有本地开发才允许在配置里显式写 {@code jwt.allowDefaultSecret: true}。
   */
  private static void requireUsableSecret(String secret) {
    boolean builtIn = secret == null || secret.isBlank() || DEV_DEFAULT_SECRET.equals(secret);
    if (!builtIn) {
      return;
    }
    if (!ConfigHolder.getBoolean("jwt.allowDefaultSecret", false)) {
      throw new IllegalStateException(
        "jwt.secret 缺失或仍是仓库内置开发密钥，拒绝启动：请注入 POMELO_JWT_SECRET；"
          + "仅本地开发可设置 jwt.allowDefaultSecret=true");
    }
    LOG.warn("JWT 正在使用仓库内置开发密钥，该密钥公开可见，禁止用于对外部署");
  }

  /** 验签并解析 token，成功返回完整 claims（sub=userId, id, userName, nickname） */
  public Future<JsonObject> validate(String token) {
    Promise<JsonObject> promise = Promise.promise();
    jwtAuth.authenticate(new TokenCredentials(token))
      .onSuccess(user -> promise.complete(user.principal()))
      .onFailure(e -> {
        LOG.debug("Token 验证失败: {}", e.getMessage());
        promise.complete(null);
      });
    return promise.future();
  }

  /** 签发 token（含身份信息，用于登录场景，避免二次查 DB） */
  public String generate(String userId, String userName, String nickname, String platform) {
    JsonObject claims = new JsonObject()
      .put("sub", userId)
      .put("id", Long.parseLong(userId))
      .put("jti", UUID.randomUUID().toString().replace("-", ""));
    if (userName != null) claims.put("userName", userName);
    if (nickname != null) claims.put("nickname", nickname);
    if (platform != null && !platform.isEmpty()) claims.put("platform", platform);
    JWTOptions options = new JWTOptions()
      .setAlgorithm("HS256")
      .setExpiresInSeconds(tokenTtlSeconds);
    return jwtAuth.generateToken(claims, options);
  }

  /** 解析 token payload 中的 jti（base64 解码，不做签名校验） */
  public String getJti(String token) {
    JsonObject payload = parsePayload(token);
    return payload != null ? payload.getString("jti") : null;
  }

  /** 解析 token payload 中的 exp（秒级 Unix 时间戳） */
  public long getExpiration(String token) {
    JsonObject payload = parsePayload(token);
    return payload != null ? payload.getLong("exp", 0L) : 0L;
  }

  private JsonObject parsePayload(String token) {
    try {
      String[] parts = token.split("\\.");
      if (parts.length < 2) return null;
      byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
      return new JsonObject(new String(decoded, StandardCharsets.UTF_8));
    } catch (Exception e) {
      return null;
    }
  }
}
