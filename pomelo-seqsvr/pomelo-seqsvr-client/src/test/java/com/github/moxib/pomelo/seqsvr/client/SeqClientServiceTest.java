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
}
