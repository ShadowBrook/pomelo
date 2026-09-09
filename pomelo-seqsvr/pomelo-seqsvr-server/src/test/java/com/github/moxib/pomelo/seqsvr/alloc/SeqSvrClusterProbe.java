package com.github.moxib.pomelo.seqsvr.alloc;

import com.github.moxib.pomelo.config.ClusterHelper;
import com.github.moxib.pomelo.seqsvr.rpc.SeqSvrAddresses;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 双进程验收探针 — 连接 Redis 集群 EventBus，向已启动的 AllocSvr 发请求。
 * <p>
 * 前置：先启动 StoreMain + SeqAllocMain（两个独立 JVM，-Dvertx.cluster=true，
 * POMELO_REDIS_CM_ENDPOINT=redis://127.0.0.1:6379）。
 * <p>
 * 类名不含 Test 后缀，常规构建不执行；显式运行：
 * {@code mvn test -Dtest=SeqSvrClusterProbe -pl pomelo-seqsvr/pomelo-seqsvr-server}
 */
class SeqSvrClusterProbe {

  @Test
  void probeFetchAndGetCurrent() throws Exception {
    Vertx vertx = ClusterHelper.createVertx();
    try {
      // 等待集群就绪 + 订阅同步 + AllocSvr 初始化
      Thread.sleep(8000);

      // 目标节点可用 -Dseqsvr.nodeId 覆盖（如 Docker 集群的 alloc-1）
      String nodeId = System.getProperty("seqsvr.nodeId", "node-1");
      JsonObject req = new JsonObject().put("id", 4242).put("version", 0);

      CountDownLatch fetched = new CountDownLatch(1);
      AtomicReference<Long> seqRef = new AtomicReference<>();
      AtomicReference<Throwable> fetchErr = new AtomicReference<>();
      vertx.eventBus().<JsonObject>request(SeqSvrAddresses.allocNodeFetchNext(nodeId), req)
        .onSuccess(resp -> {
          seqRef.set(resp.body().getLong("seq"));
          fetched.countDown();
        })
        .onFailure(err -> {
          fetchErr.set(err);
          fetched.countDown();
        });
      assertTrue(fetched.await(15, TimeUnit.SECONDS), "fetch 超时");
      assertNull(fetchErr.get(), "fetch 失败: " + fetchErr);
      assertTrue(seqRef.get() > 0, "seq 应为正数，实际 " + seqRef.get());
      System.out.println("PROBE fetchNext(id=4242) seq=" + seqRef.get());

      CountDownLatch curDone = new CountDownLatch(1);
      AtomicReference<Long> curRef = new AtomicReference<>();
      AtomicReference<Throwable> curErr = new AtomicReference<>();
      vertx.eventBus().<JsonObject>request(SeqSvrAddresses.allocNodeGetCurrent(nodeId), req)
        .onSuccess(resp -> {
          curRef.set(resp.body().getLong("seq"));
          curDone.countDown();
        })
        .onFailure(err -> {
          curErr.set(err);
          curDone.countDown();
        });
      assertTrue(curDone.await(15, TimeUnit.SECONDS), "getCurrent 超时");
      assertNull(curErr.get(), "getCurrent 失败: " + curErr);
      assertEquals(seqRef.get(), curRef.get(), "getCurrent 应与 fetchNext 一致");
      System.out.println("PROBE getCurrent(id=4242) seq=" + curRef.get());
    } finally {
      CountDownLatch closed = new CountDownLatch(1);
      vertx.close().onComplete(ar -> closed.countDown());
      closed.await(5, TimeUnit.SECONDS);
    }
  }
}
