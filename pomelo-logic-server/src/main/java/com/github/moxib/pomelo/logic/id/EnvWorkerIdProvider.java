package com.github.moxib.pomelo.logic.id;

import io.vertx.core.Future;
import io.vertx.core.Vertx;

/**
 * 从环境变量 {@code SNOWFLAKE_WORKER_ID} 读取 workerId。
 * K8s Downward API / Compose {@code environment:} 注入实例唯一 id。
 */
public class EnvWorkerIdProvider implements WorkerIdProvider {

  private static final String ENV_KEY = "SNOWFLAKE_WORKER_ID";

  @Override
  public Future<Integer> resolve(Vertx vertx) {
    return Future.succeededFuture(parse(System.getenv(ENV_KEY)));
  }

  /**
   * 解析 workerId 字符串；null / 空白 / 非法值返回 null（视为未提供）。
   */
  static Integer parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Integer.parseInt(raw.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
