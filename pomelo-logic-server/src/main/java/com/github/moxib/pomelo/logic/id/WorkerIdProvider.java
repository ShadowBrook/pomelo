package com.github.moxib.pomelo.logic.id;

import io.vertx.core.Future;
import io.vertx.core.Vertx;

/**
 * workerId 解析策略。
 *
 * Snowflake 的 workerId 必须在实例间唯一（0-1023），否则同一毫秒内生成的 ID 会碰撞。
 * 各实现提供不同的分配来源（环境变量 / Redis 租约）。
 */
public interface WorkerIdProvider {

  /**
   * 解析本实例的 workerId。
   *
   * @return workerId（0-1023）；无法确定时返回 null（由上层 fallback 到下一个来源）
   */
  Future<Integer> resolve(Vertx vertx);
}
