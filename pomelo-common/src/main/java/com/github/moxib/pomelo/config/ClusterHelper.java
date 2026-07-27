package com.github.moxib.pomelo.config;

import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.eventbus.EventBusOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Vert.x 集群工具。
 * 集群模式由 vertx-hazelcast 通过 SPI 自动发现 ClusterManager，
 * 无需显式设置 — 只要 vertx-hazelcast 在 classpath 上即可。
 *
 * 使用方式：
 *   java -Dvertx.cluster=true -jar pomelo-gateway.jar   # 集群模式
 *   java -jar pomelo-gateway.jar                          # 本地模式（默认）
 */
public final class ClusterHelper {

  private static final Logger LOG = LoggerFactory.getLogger(ClusterHelper.class);

  private ClusterHelper() {}

  /**
   * 创建 Vert.x 实例，自动检测集群模式。
   */
  public static Vertx createVertx() {
    if (!isClustered()) {
      LOG.info("Creating standalone (non-clustered) Vert.x instance");
      return Vertx.vertx();
    }

    LOG.info("Creating clustered Vert.x instance (Hazelcast auto-discovered via SPI)");
    VertxOptions options = new VertxOptions()
      .setEventBusOptions(new EventBusOptions());

    try {
      Vertx vertx = Vertx.clusteredVertx(options)
        .toCompletionStage()
        .toCompletableFuture()
        .get(60, TimeUnit.SECONDS);
      LOG.info("Clustered Vert.x started, isClustered={}", vertx.isClustered());
      return vertx;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted while waiting for clustered Vert.x", e);
    } catch (ExecutionException | TimeoutException e) {
      throw new RuntimeException("Failed to create clustered Vert.x: " + e.getMessage(), e);
    }
  }

  /**
   * 是否启用集群模式。
   */
  public static boolean isClustered() {
    return Boolean.getBoolean("vertx.cluster") || "true".equals(System.getenv("VERTX_CLUSTER"));
  }
}
