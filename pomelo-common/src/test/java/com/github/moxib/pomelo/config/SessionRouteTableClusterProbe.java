package com.github.moxib.pomelo.config;

import io.github.shadowbrook.RedisClusterManager;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 集群模式验收探针 — 校验 SessionRouteTable 复用 cluster manager 的节点身份与存活目录
 * （节点心跳已由 CM 的 nodeInfo 目录统一维护，本类不再自建心跳）。
 * <p>
 * 类名不含 Test 后缀，常规构建不执行；显式运行：
 * {@code POMELO_REDIS_CM_ENDPOINT=redis://127.0.0.1:6379 mvn test
 * -Dtest=SessionRouteTableClusterProbe -pl pomelo-common}
 */
class SessionRouteTableClusterProbe {

  @Test
  void nodeIdAndLivenessComeFromClusterManager() throws Exception {
    System.setProperty("vertx.cluster", "true");
    Vertx vertx = ClusterHelper.createVertx();
    try {
      assertTrue(vertx.isClustered(), "应以集群模式启动");
      var clusterManager = ClusterHelper.clusterManager().orElseThrow();
      String cmNodeId = clusterManager.getNodeId();
      assertNotNull(cmNodeId, "CM nodeId 应在 join 后可用");
      // nodeInfo 目录由 EventBus 启动流程异步写入，创建 Vertx 返回后可能还差几毫秒
      assertTrue(awaitCatalogEntry(clusterManager, cmNodeId), "自身应出现在 CM 节点目录中");

      SessionRouteTable routeTable = new SessionRouteTable(vertx);
      assertEquals(cmNodeId, routeTable.getNodeId(), "路由表应复用 CM 节点身份");
      assertTrue(await(routeTable.isNodeAlive(cmNodeId)), "自身节点应判活");
      assertFalse(await(routeTable.isNodeAlive(UUID.randomUUID().toString())), "未知节点应判死");

      String user = "probe-" + UUID.randomUUID();
      await(routeTable.register(user, SessionRouteTable.PLATFORM_WEB));
      assertEquals(cmNodeId, await(routeTable.resolve(user, SessionRouteTable.PLATFORM_WEB)),
        "路由应指向本节点");
      await(routeTable.unregister(user, SessionRouteTable.PLATFORM_WEB));
      assertNull(await(routeTable.resolve(user, SessionRouteTable.PLATFORM_WEB)), "注销后路由应清空");
      System.out.println("PROBE nodeId=" + cmNodeId + " 存活/路由读写校验通过");
    } finally {
      CountDownLatch closed = new CountDownLatch(1);
      vertx.close().onComplete(ar -> closed.countDown());
      assertTrue(closed.await(10, TimeUnit.SECONDS), "Vert.x 关闭超时");
    }
  }

  private static boolean awaitCatalogEntry(RedisClusterManager clusterManager, String nodeId)
    throws InterruptedException {
    for (int i = 0; i < 10; i++) {
      if (clusterManager.getNodes().contains(nodeId)) {
        return true;
      }
      Thread.sleep(500);
    }
    return false;
  }

  private static <T> T await(Future<T> future) throws Exception {
    return future.toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
  }
}
