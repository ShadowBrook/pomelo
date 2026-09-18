package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.config.SessionRouteTable;
import com.github.moxib.pomelo.model.PushCodec;
import com.github.moxib.pomelo.model.PushEnvelope;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PushRouter 投递路由测试。
 * <p>
 * 广播兜底已移除：集群模式仅精确路由，离线（无路由）/死节点/查询失败一律丢弃，
 * 由客户端 seq + ACK + PULL 离线同步补偿；单进程模式经本地 gateway.push 点对点投递。
 */
@DisplayName("PushRouter 投递路由测试")
class PushRouterTest {

  private Vertx vertx;

  @BeforeEach
  void setUp() {
    vertx = Vertx.vertx();
  }

  @AfterEach
  void tearDown() {
    if (vertx != null) {
      CountDownLatch latch = new CountDownLatch(1);
      vertx.close().onComplete(ar -> latch.countDown());
      try {
        latch.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** 可编程的假路由表：模拟集群路由可用，按用例注入路由/存活/故障 */
  private static class FakeRouteTable extends SessionRouteTable {
    String route;
    boolean nodeAlive = true;
    boolean resolveFails;
    final List<String> unregisters = new ArrayList<>();

    FakeRouteTable(Vertx vertx) {
      super(vertx, null);
    }

    @Override
    public Future<String> resolve(String userId) {
      if (resolveFails) {
        return Future.failedFuture(new RuntimeException("route store unavailable"));
      }
      return Future.succeededFuture(route);
    }

    @Override
    public Future<Boolean> isNodeAlive(String nodeId) {
      return Future.succeededFuture(nodeAlive);
    }

    @Override
    public Future<Void> unregister(String userId, String expectedNodeId) {
      unregisters.add(userId + "@" + expectedNodeId);
      return Future.succeededFuture();
    }

    @Override
    public boolean isRoutingAvailable() {
      return true;
    }
  }

  private static PushEnvelope envelope(String targetUserId) {
    PushEnvelope env = new PushEnvelope();
    env.setTargetUserId(targetUserId);
    env.setCmd(1001);
    env.setBody(new byte[]{1, 2, 3});
    return env;
  }

  /** 注册捕获 consumer，返回等待首条消息的 latch */
  private CountDownLatch consume(String address, List<PushEnvelope> received) {
    CountDownLatch latch = new CountDownLatch(1);
    vertx.eventBus().<Buffer>consumer(address, msg -> {
      received.add(PushCodec.decode(msg.body()));
      latch.countDown();
    });
    return latch;
  }

  /** 在时限内轮询等待条件成立（用于无阻塞回调的异步副作用断言） */
  private static void awaitCondition(long timeoutMs, BooleanSupplier cond)
    throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    while (!cond.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
  }

  @Test
  @DisplayName("单进程模式：经本地 gateway.push 点对点投递")
  void standaloneModeDeliversLocally() throws Exception {
    // 真实 SessionRouteTable 在非集群 Vertx 上 isRoutingAvailable=false
    PushRouter router = new PushRouter(vertx);
    List<PushEnvelope> received = new ArrayList<>();
    CountDownLatch delivered = consume("gateway.push", received);

    router.push(envelope("user-1"));

    assertTrue(delivered.await(5, TimeUnit.SECONDS), "单进程模式应经本地 gateway.push 投递");
    assertEquals("user-1", received.get(0).getTargetUserId());
  }

  @Test
  @DisplayName("集群模式：路由命中且节点存活时精确投递到节点专属地址")
  void preciseRouteDeliversToNodeAddress() throws Exception {
    FakeRouteTable routes = new FakeRouteTable(vertx);
    routes.route = "gateway-node-a";
    PushRouter router = new PushRouter(vertx, routes);

    List<PushEnvelope> received = new ArrayList<>();
    CountDownLatch delivered = consume("gateway.push.gateway-node-a", received);
    List<PushEnvelope> localReceived = new ArrayList<>();
    CountDownLatch localHit = consume("gateway.push", localReceived);

    router.push(envelope("user-2"));

    assertTrue(delivered.await(5, TimeUnit.SECONDS), "应精确投递到 gateway.push.<nodeId>");
    assertEquals("user-2", received.get(0).getTargetUserId());
    assertFalse(localHit.await(200, TimeUnit.MILLISECONDS), "不得再投递到 gateway.push 广播地址");
    assertTrue(routes.unregisters.isEmpty(), "节点存活时不得清理路由");
  }

  @Test
  @DisplayName("集群模式：目标无路由（离线）时丢弃，不投递也不清理")
  void noRouteDrops() throws Exception {
    FakeRouteTable routes = new FakeRouteTable(vertx);
    routes.route = null;
    PushRouter router = new PushRouter(vertx, routes);

    List<PushEnvelope> received = new ArrayList<>();
    CountDownLatch delivered = consume("gateway.push", received);

    router.push(envelope("user-3"));

    assertFalse(delivered.await(300, TimeUnit.MILLISECONDS), "离线目标不得收到推送");
    assertTrue(routes.unregisters.isEmpty(), "无路由时不应触发清理");
  }

  @Test
  @DisplayName("集群模式：路由指向死节点时丢弃并惰性清理该条路由")
  void deadNodeDropsAndCleansRoute() throws Exception {
    FakeRouteTable routes = new FakeRouteTable(vertx);
    routes.route = "gateway-node-b";
    routes.nodeAlive = false;
    PushRouter router = new PushRouter(vertx, routes);

    List<PushEnvelope> received = new ArrayList<>();
    CountDownLatch delivered = consume("gateway.push.gateway-node-b", received);

    router.push(envelope("user-4"));

    assertFalse(delivered.await(300, TimeUnit.MILLISECONDS), "死节点不得收到推送");
    awaitCondition(5000, () -> !routes.unregisters.isEmpty());
    assertEquals(List.of("user-4@gateway-node-b"), routes.unregisters,
      "应按解析出的死节点条件清理路由");
  }

  @Test
  @DisplayName("集群模式：路由查询失败时丢弃（由客户端离线 PULL 补偿）")
  void lookupFailureDrops() throws Exception {
    FakeRouteTable routes = new FakeRouteTable(vertx);
    routes.resolveFails = true;
    PushRouter router = new PushRouter(vertx, routes);

    List<PushEnvelope> received = new ArrayList<>();
    CountDownLatch delivered = consume("gateway.push", received);

    router.push(envelope("user-5"));

    assertFalse(delivered.await(300, TimeUnit.MILLISECONDS), "查询失败不得投递");
    assertTrue(routes.unregisters.isEmpty(), "查询失败时不应触发清理");
  }
}
