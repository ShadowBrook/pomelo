package com.github.moxib.pomelo.logic.id;

import com.github.moxib.pomelo.config.ConfigHolder;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

import java.util.List;

/**
 * workerId 解析入口：环境变量 → Redis 租约 → 配置默认。
 */
public final class WorkerIdResolver {

  private WorkerIdResolver() {}

  public static Future<Integer> resolve(Vertx vertx) {
    return resolve(vertx, List.of(new EnvWorkerIdProvider(), new RedisWorkerIdProvider()));
  }

  /** 可注入 providers 供测试。 */
  static Future<Integer> resolve(Vertx vertx, List<WorkerIdProvider> providers) {
    return resolveFrom(vertx, providers, 0);
  }

  private static Future<Integer> resolveFrom(Vertx vertx, List<WorkerIdProvider> providers, int index) {
    if (index >= providers.size()) {
      return Future.succeededFuture(ConfigHolder.getInt("snowflake.workerId", 1));
    }
    return providers.get(index).resolve(vertx)
      .compose(id -> id != null ? Future.succeededFuture(id) : resolveFrom(vertx, providers, index + 1))
      .recover(err -> resolveFrom(vertx, providers, index + 1));
  }
}
