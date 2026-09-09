package com.github.moxib.pomelo.seqsvr.client;

import com.github.moxib.pomelo.seqsvr.proto.RangeId;
import com.github.moxib.pomelo.seqsvr.proto.Router;
import com.github.moxib.pomelo.seqsvr.proto.RouterNode;
import com.github.moxib.pomelo.seqsvr.proto.SeqSvrConstants;
import com.github.moxib.pomelo.seqsvr.rpc.SeqSvrAddresses;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SeqClientService 哈希 id 映射测试。
 * <p>
 * 原实现对 snowflake 用户 id 截断低 32 位（约一半为负数被服务端拒绝），
 * 改为确定性哈希后 id 应恒为非负且落在服务端可服务的 id 空间内。
 */
@DisplayName("SeqClientService 哈希 id 映射测试")
class SeqClientServiceTest {

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

  @Test
  @DisplayName("哈希结果确定性、非负、落在 id 空间内")
  void testHashDeterministicPositiveInRange() {
    long[] keys = {
      1929383936L,               // 原始报错场景 id
      123456789L,
      1L,
      0L,
      Long.MAX_VALUE,
      0x7fffffffffffffffL,
      8806621608793784320L      // 高位为符号位的 snowflake 样式 id
    };
    for (long key : keys) {
      int id1 = SeqClientService.toSectionId(key);
      int id2 = SeqClientService.toSectionId(key);
      assertEquals(id1, id2, "同一 key 哈希应稳定: key=" + key);
      assertTrue(id1 >= 0, "id 应非负: key=" + key + " id=" + id1);
      assertTrue(id1 < SeqSvrConstants.PRODUCTION_MAX_ID_SIZE,
        "id 应小于 id 空间: key=" + key + " id=" + id1);
    }
  }

  @Test
  @DisplayName("fetchNextSequence 发送的 id 为哈希值而非截断值")
  void testFetchSendsHashedId() throws Exception {
    AtomicReference<Integer> capturedId = new AtomicReference<>();
    vertx.eventBus().consumer("seqsvr.alloc.fetchNext", msg -> {
      JsonObject body = (JsonObject) msg.body();
      capturedId.set(body.getInteger("id"));
      msg.reply(new JsonObject().put("seq", 100L));
    });

    SeqClientService client = new SeqClientService(vertx);
    long userId = 1929383936L;
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<Long> seqRef = new AtomicReference<>();
    AtomicReference<Throwable> errRef = new AtomicReference<>();
    client.fetchNextSequence(userId).onComplete(ar -> {
      if (ar.succeeded()) {
        seqRef.set(ar.result());
      } else {
        errRef.set(ar.cause());
      }
      done.countDown();
    });

    assertTrue(done.await(5, TimeUnit.SECONDS), "fetch 应完成");
    assertNull(errRef.get(), "fetch 失败: " + errRef);
    assertEquals(100L, seqRef.get(), "应返回服务端 seq");
    assertEquals(Integer.valueOf(SeqClientService.toSectionId(userId)), capturedId.get(),
      "发送的 id 应为哈希值");
    assertTrue(capturedId.get() >= 0 && capturedId.get() < SeqSvrConstants.PRODUCTION_MAX_ID_SIZE,
      "发送的 id 应在服务端可服务范围内");
  }

  private static <T> T awaitLong(Future<T> future) throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<T> ref = new AtomicReference<>();
    AtomicReference<Throwable> err = new AtomicReference<>();
    future.onComplete(ar -> {
      if (ar.succeeded()) ref.set(ar.result());
      else err.set(ar.cause());
      latch.countDown();
    });
    if (!latch.await(5, TimeUnit.SECONDS)) {
      throw new AssertionError("future timed out");
    }
    if (err.get() != null) {
      throw new AssertionError("future failed", err.get());
    }
    return ref.get();
  }

  @Test
  @DisplayName("路由过期：客户端更新路由表并重试到正确节点")
  void testRouteOutdatedRetry() throws Exception {
    // 共享兜底地址：回复 ROUTE_OUTDATED + 新路由表（node-B 拥有全部分区）
    Router newRouter = new Router(3, Collections.singletonList(
      new RouterNode("node-B", "127.0.0.1", 0,
        Collections.singletonList(new RangeId(0, SeqSvrConstants.PRODUCTION_MAX_ID_SIZE)))));
    vertx.eventBus().consumer(SeqSvrAddresses.ALLOC_FETCH_NEXT, msg ->
      msg.reply(new JsonObject()
        .put("code", SeqSvrConstants.ALLOC_CODE_ROUTE_OUTDATED)
        .put("message", "not owned")
        .put("router", JsonObject.mapFrom(newRouter))));
    // node-B 地址：成功
    vertx.eventBus().consumer(SeqSvrAddresses.allocNodeFetchNext("node-B"), msg ->
      msg.reply(new JsonObject().put("code", SeqSvrConstants.ALLOC_CODE_OK).put("seq", 100L)));

    SeqClientService client = new SeqClientService(vertx);
    long seq = awaitLong(client.fetchNextSequence(123L));
    assertEquals(100L, seq, "重试后应拿到正确节点返回的 seq");
    assertEquals(3, client.getRouteVersion(), "客户端应更新路由表版本");
    assertEquals("node-B", client.getRouter().getNodeList().get(0).getNodeId(),
      "客户端路由表应收敛到 node-B");
  }

  @Test
  @DisplayName("目标节点不可达：客户端走共享兜底地址重试一次")
  void testTransportFailureFallback() throws Exception {
    // 共享兜底地址：成功
    vertx.eventBus().consumer(SeqSvrAddresses.ALLOC_FETCH_NEXT, msg ->
      msg.reply(new JsonObject().put("code", SeqSvrConstants.ALLOC_CODE_OK).put("seq", 200L)));

    // 预置路由表指向不存在的 node-dead
    Router stale = new Router(2, Collections.singletonList(
      new RouterNode("node-dead", "127.0.0.1", 0,
        Collections.singletonList(new RangeId(0, SeqSvrConstants.PRODUCTION_MAX_ID_SIZE)))));
    SeqClientService client = new SeqClientService(vertx);
    client.setRouter(stale);

    long seq = awaitLong(client.fetchNextSequence(123L));
    assertEquals(200L, seq, "目标节点不可达时应通过共享地址兜底成功");
  }

  @Test
  @DisplayName("连续路由过期且服务端版本更低：强制采纳嵌入路由（版本倒退逃生通道）")
  void testRepeatedOutdatedForceAdoptsEmbeddedRouter() throws Exception {
    // 客户端持有 v6（线上事故：Mediate 版本倒退后服务端只回 v2）
    Router stale = new Router(6, Collections.singletonList(
      new RouterNode("node-A", "127.0.0.1", 0,
        Collections.singletonList(new RangeId(0, SeqSvrConstants.PRODUCTION_MAX_ID_SIZE)))));
    // 服务端新拓扑：node-B 拥有全部分区，但版本号只有 2
    Router newerButLowerVersion = new Router(2, Collections.singletonList(
      new RouterNode("node-B", "127.0.0.1", 0,
        Collections.singletonList(new RangeId(0, SeqSvrConstants.PRODUCTION_MAX_ID_SIZE)))));

    // node-A 一直回 ROUTE_OUTDATED + 低版本嵌入路由
    vertx.eventBus().consumer(SeqSvrAddresses.allocNodeFetchNext("node-A"), msg ->
      msg.reply(new JsonObject()
        .put("code", SeqSvrConstants.ALLOC_CODE_ROUTE_OUTDATED)
        .put("message", "not in an active section of node-A")
        .put("router", JsonObject.mapFrom(newerButLowerVersion))));
    // node-B 按新拓扑成功
    vertx.eventBus().consumer(SeqSvrAddresses.allocNodeFetchNext("node-B"), msg ->
      msg.reply(new JsonObject().put("code", SeqSvrConstants.ALLOC_CODE_OK).put("seq", 100L)));

    SeqClientService client = new SeqClientService(vertx);
    client.setRouter(stale);

    long seq = awaitLong(client.fetchNextSequence(123L));
    assertEquals(100L, seq, "强制采纳低版本路由后应在新拓扑节点成功");
    assertEquals(2, client.getRouteVersion(), "客户端应强制采纳服务端低版本路由（版本倒退场景）");
    assertEquals("node-B", client.getRouter().getNodeList().get(0).getNodeId(),
      "路由表应收敛到服务端现实拓扑");
  }

  @Test
  @DisplayName("连续 3 次路由过期：主动向 Mediate 拉取路由表并重置缓存")
  void testOutdatedLadderPullsRouterFromMediate() throws Exception {
    Router v2Router = new Router(2, Collections.singletonList(
      new RouterNode("node-B", "127.0.0.1", 0,
        Collections.singletonList(new RangeId(0, SeqSvrConstants.PRODUCTION_MAX_ID_SIZE)))));
    Router v7Router = new Router(7, Collections.singletonList(
      new RouterNode("node-C", "127.0.0.1", 0,
        Collections.singletonList(new RangeId(0, SeqSvrConstants.PRODUCTION_MAX_ID_SIZE)))));
    Router stale = new Router(6, Collections.singletonList(
      new RouterNode("node-A", "127.0.0.1", 0,
        Collections.singletonList(new RangeId(0, SeqSvrConstants.PRODUCTION_MAX_ID_SIZE)))));

    // node-A / node-B 全部回过期；Mediate 返回 v7 → node-C 成功
    vertx.eventBus().consumer(SeqSvrAddresses.allocNodeFetchNext("node-A"), msg ->
      msg.reply(new JsonObject()
        .put("code", SeqSvrConstants.ALLOC_CODE_ROUTE_OUTDATED)
        .put("message", "not owned by node-A")
        .put("router", JsonObject.mapFrom(v2Router))));
    vertx.eventBus().consumer(SeqSvrAddresses.allocNodeFetchNext("node-B"), msg ->
      msg.reply(new JsonObject()
        .put("code", SeqSvrConstants.ALLOC_CODE_ROUTE_OUTDATED)
        .put("message", "not owned by node-B")
        .put("router", JsonObject.mapFrom(v2Router))));
    vertx.eventBus().consumer(SeqSvrAddresses.MEDIATE_GET_ROUTER, msg ->
      msg.reply(new JsonObject().put("router", JsonObject.mapFrom(v7Router))));
    vertx.eventBus().consumer(SeqSvrAddresses.allocNodeFetchNext("node-C"), msg ->
      msg.reply(new JsonObject().put("code", SeqSvrConstants.ALLOC_CODE_OK).put("seq", 300L)));

    SeqClientService client = new SeqClientService(vertx);
    client.setRouter(stale);

    long seq = awaitLong(client.fetchNextSequence(123L));
    assertEquals(300L, seq, "Mediate 拉取后应在正确节点成功");
    assertEquals(7, client.getRouteVersion(), "应采纳 Mediate 返回的 v7 路由表");
  }

  @Test
  @DisplayName("路由过期响应携带 retryAfterMs：客户端延迟后再重试")
  void testOutdatedWithRetryAfterMsDelaysRetry() throws Exception {
    Router newer = new Router(8, Collections.singletonList(
      new RouterNode("node-B", "127.0.0.1", 0,
        Collections.singletonList(new RangeId(0, SeqSvrConstants.PRODUCTION_MAX_ID_SIZE)))));
    long delayMs = 300;
    long[] firstReplyAt = {0};
    vertx.eventBus().consumer(SeqSvrAddresses.ALLOC_FETCH_NEXT, msg -> {
      long now = System.currentTimeMillis();
      if (firstReplyAt[0] == 0) {
        firstReplyAt[0] = now;
        msg.reply(new JsonObject()
          .put("code", SeqSvrConstants.ALLOC_CODE_ROUTE_OUTDATED)
          .put("message", "stop serving, retry later")
          .put("retryAfterMs", delayMs)
          .put("router", JsonObject.mapFrom(newer)));
      } else {
        msg.reply(new JsonObject().put("code", SeqSvrConstants.ALLOC_CODE_OK).put("seq", 400L));
      }
    });

    SeqClientService client = new SeqClientService(vertx);
    long start = System.currentTimeMillis();
    long seq = awaitLong(client.fetchNextSequence(123L));
    long elapsed = System.currentTimeMillis() - start;

    assertEquals(400L, seq);
    assertTrue(elapsed >= delayMs,
      "重试应被 retryAfterMs 推迟: elapsed=" + elapsed + "ms, delay=" + delayMs + "ms");
  }
}
