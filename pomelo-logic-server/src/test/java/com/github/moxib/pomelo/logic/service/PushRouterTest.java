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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PushRouter 投递路由测试。
 * <p>
 * 广播兜底已移除：集群模式仅精确路由，按端型扇出（每端型一条路由各投一次），
 * 离线（无路由）/死节点/查询失败一律丢弃，由客户端 seq + ACK + PULL 离线同步补偿；
 * 单进程模式经本地 gateway.push 点对点投递（信封不带端型，gateway 投全部端会话）。
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

  /** 可编程的假路由表：模拟集群路由可用，按用例注入端型路由/存活/故障 */
  private static class FakeRouteTable extends SessionRouteTable {
    Map<String, String> routes = Map.of();
    Set<String> deadNodes = Set.of();
    boolean resolveFails;
    final List<String> unregisters = new ArrayList<>();

    FakeRouteTable(Vertx vertx) {
      super(vertx, null);
    }

    @Override
    public Future<Map<String, String>> resolveAll(String userId) {
      if (resolveFails) {
        return Future.failedFuture(new RuntimeException("route store unavailable"));
      }
      return Future.succeededFuture(routes);
    }

    @Override
    public Future<Boolean> isNodeAlive(String nodeId) {
      return Future.succeededFuture(!deadNodes.contains(nodeId));
    }

    @Override
    public Future<Void> unregister(String userId, String platform, String expectedNodeId) {
      unregisters.add(userId + ":" + platform + "@" + expectedNodeId);
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
  @DisplayName("单进程模式：经本地 gateway.push 点对点投递，信封不带端型")
  void standaloneModeDeliversLocally() throws Exception {
    // 真实 SessionRouteTable 在非集群 Vertx 上 isRoutingAvailable=false
    PushRouter router = new PushRouter(vertx);
    List<PushEnvelope> received = new ArrayList<>();
    CountDownLatch delivered = consume("gateway.push", received);

    router.push(envelope("user-1"));

    assertTrue(delivered.await(5, TimeUnit.SECONDS), "单进程模式应经本地 gateway.push 投递");
    assertEquals("user-1", received.get(0).getTargetUserId());
    assertEquals("", received.get(0).getTargetPlatform(), "单进程信封不应带端型（gateway 投全部端会话）");
  }

  @Test
  @DisplayName("集群模式：多端型各投一次，信封分别携带对应端型")
  void fanoutDeliversPerPlatform() throws Exception {
    FakeRouteTable routes = new FakeRouteTable(vertx);
    routes.routes = Map.of(
      SessionRouteTable.PLATFORM_WEB, "gateway-node-a",
      SessionRouteTable.PLATFORM_ANDROID, "gateway-node-a");
    PushRouter router = new PushRouter(vertx, routes);

    List<PushEnvelope> received = new ArrayList<>();
    CountDownLatch delivered = consume("gateway.push.gateway-node-a", received);

    router.push(envelope("user-2"));

    assertTrue(delivered.await(5, TimeUnit.SECONDS), "应投递到 gateway.push.<nodeId>");
    awaitCondition(5000, () -> received.size() >= 2);
    assertEquals(2, received.size(), "两个端型各投一次");
    assertEquals(Set.of(SessionRouteTable.PLATFORM_WEB, SessionRouteTable.PLATFORM_ANDROID),
      setOfPlatforms(received), "两份信封应分别携带 web/android 端型");
    for (PushEnvelope env : received) {
      assertEquals("user-2", env.getTargetUserId());
      assertArrayEquals(new byte[]{1, 2, 3}, env.getBody(), "各端信封共享同一 body");
    }
    assertTrue(routes.unregisters.isEmpty(), "节点存活时不得清理路由");
  }

  @Test
  @DisplayName("集群模式：不同端型路由到不同节点时各投各的节点地址")
  void fanoutAcrossNodes() throws Exception {
    FakeRouteTable routes = new FakeRouteTable(vertx);
    routes.routes = Map.of(
      SessionRouteTable.PLATFORM_WEB, "gateway-node-a",
      SessionRouteTable.PLATFORM_ANDROID, "gateway-node-b");
    PushRouter router = new PushRouter(vertx, routes);

    List<PushEnvelope> nodeA = new ArrayList<>();
    CountDownLatch hitA = consume("gateway.push.gateway-node-a", nodeA);
    List<PushEnvelope> nodeB = new ArrayList<>();
    CountDownLatch hitB = consume("gateway.push.gateway-node-b", nodeB);

    router.push(envelope("user-3"));

    assertTrue(hitA.await(5, TimeUnit.SECONDS), "web 路由应投到节点 A");
    assertTrue(hitB.await(5, TimeUnit.SECONDS), "android 路由应投到节点 B");
    assertEquals(SessionRouteTable.PLATFORM_WEB, nodeA.get(0).getTargetPlatform());
    assertEquals(SessionRouteTable.PLATFORM_ANDROID, nodeB.get(0).getTargetPlatform());
  }

  @Test
  @DisplayName("集群模式：目标无路由（离线）时丢弃，不投递也不清理")
  void noRouteDrops() throws Exception {
    FakeRouteTable routes = new FakeRouteTable(vertx);
    routes.routes = Map.of();
    PushRouter router = new PushRouter(vertx, routes);

    List<PushEnvelope> received = new ArrayList<>();
    CountDownLatch delivered = consume("gateway.push", received);

    router.push(envelope("user-4"));

    assertFalse(delivered.await(300, TimeUnit.MILLISECONDS), "离线目标不得收到推送");
    assertTrue(routes.unregisters.isEmpty(), "无路由时不应触发清理");
  }

  @Test
  @DisplayName("集群模式：单端型路由指向死节点时仅丢弃该端并惰性清理该条路由")
  void deadNodeDropsAndCleansOnlyThatPlatform() throws Exception {
    FakeRouteTable routes = new FakeRouteTable(vertx);
    routes.routes = Map.of(
      SessionRouteTable.PLATFORM_WEB, "gateway-node-dead",
      SessionRouteTable.PLATFORM_ANDROID, "gateway-node-alive");
    routes.deadNodes = Set.of("gateway-node-dead");
    PushRouter router = new PushRouter(vertx, routes);

    List<PushEnvelope> dead = new ArrayList<>();
    CountDownLatch deadHit = consume("gateway.push.gateway-node-dead", dead);
    List<PushEnvelope> alive = new ArrayList<>();
    CountDownLatch aliveHit = consume("gateway.push.gateway-node-alive", alive);

    router.push(envelope("user-5"));

    assertTrue(aliveHit.await(5, TimeUnit.SECONDS), "存活端型仍应正常投递");
    assertEquals(SessionRouteTable.PLATFORM_ANDROID, alive.get(0).getTargetPlatform());
    assertFalse(deadHit.await(300, TimeUnit.MILLISECONDS), "死节点端型不得收到推送");
    awaitCondition(5000, () -> !routes.unregisters.isEmpty());
    assertEquals(List.of("user-5:" + SessionRouteTable.PLATFORM_WEB + "@gateway-node-dead"),
      routes.unregisters, "应仅按死节点端型条件清理路由");
  }

  @Test
  @DisplayName("集群模式：路由查询失败时丢弃（由客户端离线 PULL 补偿）")
  void lookupFailureDrops() throws Exception {
    FakeRouteTable routes = new FakeRouteTable(vertx);
    routes.resolveFails = true;
    PushRouter router = new PushRouter(vertx, routes);

    List<PushEnvelope> received = new ArrayList<>();
    CountDownLatch delivered = consume("gateway.push", received);

    router.push(envelope("user-6"));

    assertFalse(delivered.await(300, TimeUnit.MILLISECONDS), "查询失败不得投递");
    assertTrue(routes.unregisters.isEmpty(), "查询失败时不应触发清理");
  }

  private static Set<String> setOfPlatforms(List<PushEnvelope> received) {
    Set<String> set = new HashSet<>();
    for (PushEnvelope env : received) {
      set.add(env.getTargetPlatform());
    }
    return set;
  }
}
