package com.github.moxib.pomelo.logic.infrastructure;

import com.github.moxib.pomelo.config.ConfigHolder;
import com.github.moxib.pomelo.config.JwtTokenParser;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JWT Token 服务 — 复用 {@link JwtTokenParser} 做验签/解析/签发，本类只负责黑名单（Redis）。
 * - 签发：HTTP 登录成功后生成 token
 * - 验证：WebSocket/TCP AUTH_REQ 时校验 token，提取 userId
 * - 撤销：登出时将 jti 加入 Redis 黑名单
 */
public class TokenService {

  private static final Logger LOG = LoggerFactory.getLogger(TokenService.class);

  private static volatile TokenService instance;

  private final JwtTokenParser parser;
  private final Vertx vertx;
  private final String blacklistPrefix;

  private TokenService(Vertx vertx) {
    this.vertx = vertx;
    this.parser = new JwtTokenParser(vertx);
    this.blacklistPrefix = ConfigHolder.getString("jwt.blacklistPrefix", "jwt:blacklist:");
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

  /** 签发 token（含身份信息），避免 Handler 二次查 DB */
  public String generate(String userId, String userName, String nickname, String platform) {
    return parser.generate(userId, userName, nickname, platform);
  }

  /** 验证并解析 token，成功返回完整 claims（JsonObject） */
  public Future<JsonObject> validate(String token) {
    return parser.validate(token);
  }

  /** 解析 token 中的 jti（用于黑名单，同步解析 payload） */
  public String getJti(String token) {
    return parser.getJti(token);
  }

  /** 解析 token 中的 exp（秒级 Unix 时间戳） */
  public long getExpiration(String token) {
    return parser.getExpiration(token);
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
