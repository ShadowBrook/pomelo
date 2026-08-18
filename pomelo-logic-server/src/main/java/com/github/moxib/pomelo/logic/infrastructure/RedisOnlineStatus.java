package com.github.moxib.pomelo.logic.infrastructure;

import com.github.moxib.pomelo.config.ConfigHolder;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.RedisConnection;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static io.vertx.redis.client.Command.SADD;
import static io.vertx.redis.client.Command.SISMEMBER;
import static io.vertx.redis.client.Command.SREM;

/**
 * 用户在线状态管理（基于 Redis SET: im:online:users）。
 * 单一职责：仅负责在线状态的读写，连接由 {@link RedisFactory} 提供。
 */
public final class RedisOnlineStatus {

  private static final Logger LOG = LoggerFactory.getLogger(RedisOnlineStatus.class);

  private static volatile RedisOnlineStatus instance;
  private final Vertx vertx;
  private final String onlineSetKey;

  private RedisOnlineStatus(Vertx vertx) {
    this.vertx = vertx;
    this.onlineSetKey = ConfigHolder.getString("redis.onlineSetKey", "im:online:users");
  }

  /** 获取单例 */
  public static RedisOnlineStatus get(Vertx vertx) {
    if (instance == null) {
      synchronized (RedisOnlineStatus.class) {
        if (instance == null) {
          instance = new RedisOnlineStatus(vertx);
        }
      }
    }
    return instance;
  }

  /** 获取共享的 Redis 连接 */
  private RedisConnection connection() {
    return RedisFactory.get(vertx).getConnection();
  }

  /** 用户上线 */
  public Future<Void> setOnline(String userId) {
    Promise<Void> promise = Promise.promise();
    connection().send(Request.cmd(SADD).arg(onlineSetKey).arg(userId))
      .onSuccess(r -> {
        LOG.debug("用户 {} 上线（Redis SET）", userId);
        promise.complete();
      })
      .onFailure(e -> {
        LOG.warn("设置用户在线状态失败: {}", e.getMessage());
        promise.fail(e);
      });
    return promise.future();
  }

  /** 用户下线 */
  public Future<Void> setOffline(String userId) {
    Promise<Void> promise = Promise.promise();
    connection().send(Request.cmd(SREM).arg(onlineSetKey).arg(userId))
      .onSuccess(r -> {
        LOG.debug("用户 {} 下线（Redis SET）", userId);
        promise.complete();
      })
      .onFailure(e -> {
        LOG.warn("设置用户离线状态失败: {}", e.getMessage());
        promise.fail(e);
      });
    return promise.future();
  }

  /** 检查用户是否在线 */
  public Future<Boolean> isOnline(String userId) {
    Promise<Boolean> promise = Promise.promise();
    connection().send(Request.cmd(SISMEMBER).arg(onlineSetKey).arg(userId))
      .onSuccess(r -> promise.complete(r != null && r.toInteger() == 1))
      .onFailure(e -> {
        LOG.warn("查询在线状态失败: {}", e.getMessage());
        // 默认离线
        promise.complete(false);
      });
    return promise.future();
  }

  /**
   * 批量检查用户在线状态（单次 Redis SMISMEMBER 调用）。
   * 返回的列表与输入列表顺序一致，true=在线。
   */
  public Future<List<Boolean>> batchIsOnline(List<String> userIds) {
    if (userIds == null || userIds.isEmpty()) {
      return Future.succeededFuture(List.of());
    }
    Promise<List<Boolean>> promise = Promise.promise();
    Request req = Request.cmd(Command.create("SMISMEMBER")).arg(onlineSetKey);
    for (String uid : userIds) {
      req.arg(uid);
    }
    connection().send(req)
      .onSuccess(r -> {
        List<Boolean> result = new ArrayList<>(userIds.size());
        if (r != null) {
          for (Response item : r) {
            result.add(item.toInteger() == 1);
          }
        }
        promise.complete(result);
      })
      .onFailure(e -> {
        LOG.warn("批量查询在线状态失败: {}", e.getMessage());
        // 降级：全部返回离线
        List<Boolean> fallback = new ArrayList<>(userIds.size());
        for (int i = 0; i < userIds.size(); i++) fallback.add(false);
        promise.complete(fallback);
      });
    return promise.future();
  }
}
