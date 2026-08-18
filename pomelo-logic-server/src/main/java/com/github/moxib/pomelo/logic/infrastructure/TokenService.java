package com.github.moxib.pomelo.logic.infrastructure;

import com.github.moxib.pomelo.config.ConfigHolder;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.PubSecKeyOptions;
import io.vertx.ext.auth.authentication.TokenCredentials;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * JWT Token 服务（基于 Vert.x vertx-auth-jwt）。
 * - 签发：HTTP 登录成功后生成 token
 * - 验证：WebSocket/TCP AUTH_REQ 时校验 token，提取 userId
 * - 撤销：登出时将 jti 加入 Redis 黑名单
 */
public class TokenService {

  private static final Logger LOG = LoggerFactory.getLogger(TokenService.class);

  private static volatile TokenService instance;

  private final JWTAuth jwtAuth;
  private final Vertx vertx;
  private final int tokenTtlSeconds;
  private final String blacklistPrefix;

  private TokenService(Vertx vertx) {
    this.vertx = vertx;
    this.tokenTtlSeconds = ConfigHolder.getInt("jwt.ttlSeconds", 86400);
    this.blacklistPrefix = ConfigHolder.getString("jwt.blacklistPrefix", "jwt:blacklist:");
    String secret = ConfigHolder.getString("jwt.secret", "pomelo-dev-secret-change-in-production");
    PubSecKeyOptions keyOptions = new PubSecKeyOptions()
      .setAlgorithm("HS256")
      .setBuffer(secret);
    JWTAuthOptions config = new JWTAuthOptions().addPubSecKey(keyOptions);
    this.jwtAuth = JWTAuth.create(vertx, config);
  }

  /** 获取单例 */
  public static TokenService get(Vertx vertx) {
    if (instance == null) {
      synchronized (TokenService.class) {
        if (instance == null) {
          instance = new TokenService(vertx);
        }
      }
    }
    return instance;
  }

  /** 签发 token（仅 userId，用于注册场景） */
  public String generate(String userId) {
    return generate(userId, 0, null, null);
  }

  /** 签发 token（含身份信息，用于登录场景，避免 LoginHandler 二次查 DB） */
  public String generate(String userId, long id, String userName, String nickname) {
    JsonObject claims = new JsonObject()
      .put("sub", userId)
      .put("jti", UUID.randomUUID().toString().replace("-", ""));
    if (id != 0) claims.put("id", id);
    if (userName != null) claims.put("userName", userName);
    if (nickname != null) claims.put("nickname", nickname);
    JWTOptions options = new JWTOptions()
      .setAlgorithm("HS256")
      .setExpiresInSeconds(tokenTtlSeconds);
    return jwtAuth.generateToken(claims, options);
  }

  /** 验证并解析 token，成功返回完整 claims（JsonObject） */
  public Future<JsonObject> validate(String token) {
    Promise<JsonObject> promise = Promise.promise();
    jwtAuth.authenticate(new TokenCredentials(token))
      .onSuccess(user -> {
        LOG.debug("Token 验证成功: sub={}", user.principal().getString("sub"));
        promise.complete(user.principal());
      })
      .onFailure(e -> {
        LOG.debug("Token 验证失败: {}", e.getMessage());
        promise.complete(null);
      });
    return promise.future();
  }

  /** 解析 token 中的 jti（用于黑名单，同步解析 payload） */
  public String getJti(String token) {
    try {
      String[] parts = token.split("\\.");
      if (parts.length < 2) return null;
      String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
      JsonObject json = new JsonObject(payload);
      return json.getString("jti");
    } catch (Exception e) {
      return null;
    }
  }

  /** 解析 token 中的 exp（秒级 Unix 时间戳） */
  public long getExpiration(String token) {
    try {
      String[] parts = token.split("\\.");
      if (parts.length < 2) return 0;
      String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
      return new JsonObject(payload).getLong("exp", 0L);
    } catch (Exception e) {
      return 0;
    }
  }

  /** 将 token 加入 Redis 黑名单 */
  public Future<Void> blacklist(String token) {
    Promise<Void> promise = Promise.promise();
    String jti = getJti(token);
    if (jti == null) {
      promise.complete();
      return promise.future();
    }
    long ttl = Math.max(1, getExpiration(token) - System.currentTimeMillis() / 1000);
    RedisFactory.get(vertx).getConnection().send(
      Request.cmd(Command.SET)
        .arg(blacklistPrefix + jti).arg("1")
        .arg("EX").arg(String.valueOf(ttl)))
      .onSuccess(r -> LOG.debug("Token 已加入黑名单: jti={} ttl={}s", jti, ttl))
      .onFailure(e -> LOG.warn("Token 黑名单失败: {}", e.getMessage()));
    promise.complete();
    return promise.future();
  }

  /** 检查 token 是否在黑名单中 */
  public Future<Boolean> isBlacklisted(String token) {
    Promise<Boolean> promise = Promise.promise();
    String jti = getJti(token);
    if (jti == null) {
      promise.complete(false);
      return promise.future();
    }
    RedisFactory.get(vertx).getConnection().send(
      Request.cmd(Command.EXISTS)
        .arg(blacklistPrefix + jti))
      .onSuccess(r -> promise.complete(r != null && r.toInteger() == 1))
      .onFailure(e -> {
        LOG.warn("黑名单检查失败: {}", e.getMessage());
        promise.complete(false);
      });
    return promise.future();
  }
}
