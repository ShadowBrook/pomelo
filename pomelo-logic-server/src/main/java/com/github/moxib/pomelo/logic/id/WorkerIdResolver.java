package com.github.moxib.pomelo.logic.id;

import com.github.moxib.pomelo.config.ConfigHolder;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * workerId 解析入口：环境变量 → Redis 租约 → 显式配置的静态值。
 */
public final class WorkerIdResolver {

  private static final Logger LOG = LoggerFactory.getLogger(WorkerIdResolver.class);

  private WorkerIdResolver() {}

  public static Future<Integer> resolve(Vertx vertx) {
    RedisWorkerIdProvider redis = new RedisWorkerIdProvider();
    redis.onLeaseLost(workerId -> failFast(vertx, workerId));
    return resolve(vertx, List.of(new EnvWorkerIdProvider(), redis));
  }

  /** 可注入 providers 供测试。 */
  static Future<Integer> resolve(Vertx vertx, List<WorkerIdProvider> providers) {
    return resolveFrom(vertx, providers, 0);
  }

  private static Future<Integer> resolveFrom(Vertx vertx, List<WorkerIdProvider> providers, int index) {
    if (index >= providers.size()) {
      return staticWorkerIdOrFail();
    }
    return providers.get(index).resolve(vertx)
      .compose(id -> id != null ? Future.succeededFuture(id) : resolveFrom(vertx, providers, index + 1))
      .recover(err -> {
        LOG.warn("workerId provider[{}] 解析失败，尝试下一个: {}", index, err.getMessage());
        return resolveFrom(vertx, providers, index + 1);
      });
  }

  /**
   * 全部 provider 都没给出 workerId 时不静默回退到配置常量：
   * 多节点同时回退会拿到同一个 workerId，进而对同一毫秒生成重复 Snowflake ID
   * （用户主键、消息主键），后果是重复注册与消息落库失败。
   * 只有显式声明单节点时才允许用静态值。
   */
  private static Future<Integer> staticWorkerIdOrFail() {
    if (!ConfigHolder.getBoolean("snowflake.allowStaticWorkerId", false)) {
      return Future.failedFuture(new IllegalStateException(
        "workerId 解析失败：环境变量与 Redis 租约均不可用。多节点回退到同一常量会产生重复 ID，"
          + "故拒绝启动；单节点部署可显式设置 snowflake.allowStaticWorkerId=true"));
    }
    int configured = ConfigHolder.getInt("snowflake.workerId", 1);
    LOG.warn("workerId 未从环境变量/Redis 解析到，按 snowflake.allowStaticWorkerId 使用静态值 {}（仅适用于单节点）", configured);
    return Future.succeededFuture(configured);
  }

  /** 租约丢失即停服：继续发号可能生成重复 ID，损坏消息主键与用户主键 */
  private static void failFast(Vertx vertx, int workerId) {
    LOG.error("workerId {} 租约已丢失（slot 可能已被其他节点占用），停止服务以免生成重复 ID", workerId);
    vertx.close().onComplete(ar -> System.exit(1));
  }
}
