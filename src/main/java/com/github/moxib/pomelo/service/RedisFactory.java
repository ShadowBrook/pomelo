package com.github.moxib.pomelo.service;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisConnection;
import io.vertx.redis.client.RedisOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis 连接工厂（单例）。
 * 单一职责：仅管理 Redis 连接生命周期。在线状态操作请使用 {@link RedisOnlineStatus}。
 */
public final class RedisFactory {

  private static final Logger LOG = LoggerFactory.getLogger(RedisFactory.class);

  private static volatile RedisFactory instance;
  private final Redis redis;
  private volatile RedisConnection connection;

  private RedisFactory(Vertx vertx) {
    RedisOptions options = new RedisOptions()
      .setMaxPoolSize(4)
      .setMaxWaitingHandlers(8);
    this.redis = Redis.createClient(vertx, options);
  }

  /** 获取单例 */
  public static RedisFactory get(Vertx vertx) {
    if (instance == null) {
      synchronized (RedisFactory.class) {
        if (instance == null) {
          instance = new RedisFactory(vertx);
        }
      }
    }
    return instance;
  }

  /** 确保 Redis 连接已建立 */
  public Future<Void> connect() {
    if (connection != null) {
      return Future.succeededFuture();
    }
    Promise<Void> promise = Promise.promise();
    redis.connect()
      .onSuccess(conn -> {
        this.connection = conn;
        LOG.info("RedisFactory 已连接");
        promise.complete();
      })
      .onFailure(e -> {
        LOG.error("RedisFactory 连接失败", e);
        promise.fail(e);
      });
    return promise.future();
  }

  /** 获取连接（需先调用 connect） */
  public RedisConnection getConnection() {
    return connection;
  }

  /** 获取 Redis 客户端 */
  public Redis getRedis() {
    return redis;
  }

  /** 关闭连接（应用关闭时调用） */
  public void close() {
    if (connection != null) {
      connection.close();
      connection = null;
    }
    if (redis != null) {
      redis.close();
    }
    LOG.info("RedisFactory 已关闭");
  }
}
