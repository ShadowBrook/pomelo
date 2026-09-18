package com.github.moxib.pomelo.seqsvr.mediate;

import com.github.moxib.pomelo.metrics.PomeloMetrics;
import com.github.moxib.pomelo.seqsvr.proto.RangeId;
import com.github.moxib.pomelo.seqsvr.proto.Router;
import com.github.moxib.pomelo.seqsvr.proto.RouterNode;
import com.github.moxib.pomelo.seqsvr.proto.SeqSvrConstants;
import com.github.moxib.pomelo.seqsvr.rpc.StoreAccessor;
import io.vertx.core.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * mediate 域指标经 Prometheus registry 暴露（MediateVerticle /metrics 端点输出内容）。
 */
@DisplayName("Mediate 域指标暴露")
class MediateMetricsTest {

  private static final int MAX = SeqSvrConstants.DEBUG_MAX_ID_SIZE;

  /** 内存 StoreAccessor：仅记录最近保存的路由表 */
  private static class MemStore implements StoreAccessor {
    volatile Router saved;

    @Override
    public Future<long[]> loadMaxSeqsData() {
      return Future.succeededFuture(new long[0]);
    }

    @Override
    public Future<Long> saveMaxSeq(int id, long maxSeq) {
      return Future.succeededFuture(maxSeq);
    }

    @Override
    public Future<Router> loadRouteTable() {
      return Future.succeededFuture(saved == null ? new Router(0, new ArrayList<>()) : saved);
    }

    @Override
    public Future<Void> saveRouteTable(Router router) {
      this.saved = router;
      return Future.succeededFuture();
    }
  }

  private static RouterNode node(String id) {
    return new RouterNode(id, "127.0.0.1", 0,
      Collections.singletonList(new RangeId(0, MAX)));
  }

  @Test
  @DisplayName("注册后 nodes / router.version gauge 出现在 Prometheus 输出中")
  void mediateGaugesExposedViaPrometheusScrape() throws Exception {
    MediateManager manager = new MediateManager(new MemStore(), new RangeId(0, MAX));

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Throwable> err = new AtomicReference<>();
    manager.register(node("node-1")).onComplete(ar -> {
      if (ar.failed()) err.set(ar.cause());
      latch.countDown();
    });
    if (!latch.await(10, TimeUnit.SECONDS)) {
      throw new AssertionError("register timed out");
    }
    if (err.get() != null) {
      throw new AssertionError("register failed", err.get());
    }

    String scraped = PomeloMetrics.scrape();
    assertTrue(scraped.contains("seqsvr_mediate_nodes"), "应包含 nodes gauge");
    assertTrue(scraped.contains("seqsvr_mediate_router_version"), "应包含 router version gauge");

    assertEquals(1.0, PomeloMetrics.registry().get("seqsvr.mediate.nodes").gauge().value(),
      "注册一个节点后 nodes 应为 1");
    assertTrue(PomeloMetrics.registry().get("seqsvr.mediate.router.version").gauge().value() >= 1,
      "注册后路由版本应 >= 1");
  }
}
