package com.github.moxib.pomelo.seqsvr.alloc;

import com.github.moxib.pomelo.seqsvr.proto.SeqSvrConstants;
import com.github.moxib.pomelo.seqsvr.store.StoreVerticle;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单节点发号性能基准（Phase 5）— 本机 EventBus 全链路（Store + Alloc 同进程）。
 * <p>
 * 类名不含 Test 后缀，常规构建不执行；显式运行：
 * {@code mvn test -Dtest=SeqSvrPerfBenchmark -pl pomelo-seqsvr/pomelo-seqsvr-server}
 * <p>
 * 指标：QPS、P50/P99/max 延迟（毫秒）。发号路径纯内存 + EventBus 往返，
 * saveMaxSeq 每 10000 次才一次（写放大 = QPS/10000）。
 */
class SeqSvrPerfBenchmark {

  private static final int MAX = SeqSvrConstants.DEBUG_MAX_ID_SIZE;

  @Test
  void benchmark() throws Exception {
    Vertx vertx = Vertx.vertx();
    try {
      CountDownLatch deployed = new CountDownLatch(1);
      AtomicInteger deployErr = new AtomicInteger();
      vertx.deployVerticle(new StoreVerticle())
        .compose(id -> vertx.deployVerticle(new SeqAllocVerticle(
          new SeqAllocConfig(MAX, 0, MAX, "node-1", "127.0.0.1", 0,
            "seqsvr.store", false, "seqsvr.mediate", 1000, 4000, 1000, 5000))))
        .onSuccess(id -> deployed.countDown())
        .onFailure(e -> {
          deployErr.set(1);
          System.err.println("deploy failed: " + e);
          deployed.countDown();
        });
      if (!deployed.await(20, TimeUnit.SECONDS) || deployErr.get() != 0) {
        throw new AssertionError("deploy failed");
      }

      // 预热
      run(vertx, 10_000, 100);

      // 压测
      int total = 200_000;
      int concurrency = 200;
      long start = System.nanoTime();
      long[] latenciesNanos = run(vertx, total, concurrency);
      long elapsedNanos = System.nanoTime() - start;

      double qps = total * 1e9 / elapsedNanos;
      Arrays.sort(latenciesNanos);
      double p50 = latenciesNanos[(int) (total * 0.50)] / 1e6;
      double p99 = latenciesNanos[(int) (total * 0.99)] / 1e6;
      double max = latenciesNanos[total - 1] / 1e6;

      System.out.printf("BENCH total=%d concurrency=%d qps=%.0f p50=%.3fms p99=%.3fms max=%.3fms " +
          "(estimated saveMaxSeq writes=%.0f)%n",
        total, concurrency, qps, p50, p99, max, total / 10000.0);

      // 宽松下限：本地 EventBus 全链路至少 1 万 QPS（防止明显回归）
      if (qps < 10_000) {
        throw new AssertionError("QPS too low: " + qps);
      }
    } finally {
      CountDownLatch closed = new CountDownLatch(1);
      vertx.close().onComplete(ar -> closed.countDown());
      closed.await(5, TimeUnit.SECONDS);
    }
  }

  private static long[] run(Vertx vertx, int total, int concurrency) throws InterruptedException {
    long[] latencies = new long[total];
    AtomicInteger sent = new AtomicInteger();
    AtomicInteger completed = new AtomicInteger();
    CountDownLatch done = new CountDownLatch(1);

    for (int i = 0; i < Math.min(concurrency, total); i++) {
      sendOne(vertx, sent, completed, latencies, total, done);
    }
    if (!done.await(120, TimeUnit.SECONDS)) {
      throw new AssertionError("benchmark timed out: sent=" + sent.get() + ", completed=" + completed.get());
    }
    return latencies;
  }

  private static void sendOne(Vertx vertx, AtomicInteger sent, AtomicInteger completed, long[] latencies,
                              int total, CountDownLatch done) {
    int idx = sent.getAndIncrement();
    if (idx >= total) {
      if (completed.get() >= total) {
        done.countDown();
      }
      return;
    }
    long start = System.nanoTime();
    JsonObject req = new JsonObject().put("id", idx % SeqSvrConstants.DEBUG_MAX_ID_SIZE).put("version", 0);
    vertx.eventBus().<JsonObject>request("seqsvr.alloc.node-1.fetchNext", req)
      .onComplete(ar -> {
        latencies[idx] = System.nanoTime() - start;
        completed.incrementAndGet();
        // 维持并发度：每个完成触发下一个
        sendOne(vertx, sent, completed, latencies, total, done);
      });
  }
}
