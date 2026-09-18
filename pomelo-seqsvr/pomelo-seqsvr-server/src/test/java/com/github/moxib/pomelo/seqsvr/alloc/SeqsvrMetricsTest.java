package com.github.moxib.pomelo.seqsvr.alloc;

import com.github.moxib.pomelo.metrics.PomeloMetrics;
import com.github.moxib.pomelo.seqsvr.proto.RangeId;
import com.github.moxib.pomelo.seqsvr.proto.RouterNode;
import com.github.moxib.pomelo.seqsvr.proto.SeqSvrConstants;
import com.github.moxib.pomelo.seqsvr.rpc.StoreAccessor;
import com.github.moxib.pomelo.seqsvr.store.LocalStoreAccessor;
import com.github.moxib.pomelo.seqsvr.store.StoreManager;
import io.vertx.core.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * alloc 域指标经 Prometheus registry 暴露（SeqAllocVerticle /metrics 端点输出内容）。
 */
@DisplayName("Alloc 域指标暴露")
class SeqsvrMetricsTest {

  private Path tempDir;
  private StoreManager storeManager;

  @BeforeEach
  void setUp() throws Exception {
    tempDir = Files.createTempDirectory("seqsvr-test-metrics-");
    storeManager = new StoreManager(new RangeId(0, SeqSvrConstants.DEBUG_MAX_ID_SIZE), tempDir.toString());
  }

  @AfterEach
  void tearDown() throws IOException {
    if (storeManager != null) {
      storeManager.close();
    }
    try (var files = Files.walk(tempDir)) {
      files.sorted(Comparator.reverseOrder()).forEach(p -> {
        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
      });
    }
  }

  @Test
  @DisplayName("发号后 fetch 计数 / serving / 号段 gauge 出现在 Prometheus 输出中")
  void fetchMetricsExposedViaPrometheusScrape() throws Exception {
    StoreAccessor store = new LocalStoreAccessor(storeManager);
    RouterNode myNode = new RouterNode("node-1", "127.0.0.1", 0,
      Collections.singletonList(new RangeId(0, SeqSvrConstants.DEBUG_MAX_ID_SIZE)));
    AllocManager alloc = new AllocManager(store,
      new RangeId(0, SeqSvrConstants.DEBUG_MAX_ID_SIZE), myNode, SeqSvrConstants.DEBUG_MAX_ID_SIZE);
    await(alloc.init());

    // registry 为 JVM 级单例，同模块其他测试可能已累计计数，断言增量而非绝对值
    var counter = PomeloMetrics.registry().get("seqsvr.alloc.fetch.total").tag("node", "node-1").counter();
    double before = counter.count();
    alloc.fetchNextSequence(100, 0);
    assertEquals(before + 1, counter.count(), "发号一次后计数应 +1");

    String scraped = PomeloMetrics.scrape();
    assertTrue(scraped.contains("seqsvr_alloc_fetch_total"), "应包含 fetch 计数");
    assertTrue(scraped.contains("seqsvr_alloc_serving"), "应包含 serving gauge");
    assertTrue(scraped.contains("seqsvr_alloc_sections_active"), "应包含 active 号段 gauge");
    assertTrue(scraped.contains("seqsvr_alloc_lease_last_success_timestamp"), "应包含租约心跳 gauge");

    // gauge 首注册者优先（同 JVM 多测试实例），只断言取值域
    double serving = PomeloMetrics.registry().get("seqsvr.alloc.serving").gauge().value();
    assertTrue(serving == 0 || serving == 1, "serving 应为 0/1，实际 " + serving);
  }

  private static void await(Future<Void> future) throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Throwable> err = new AtomicReference<>();
    future.onComplete(ar -> {
      if (ar.failed()) err.set(ar.cause());
      latch.countDown();
    });
    if (!latch.await(10, TimeUnit.SECONDS)) {
      throw new AssertionError("future timed out");
    }
    if (err.get() != null) {
      throw new AssertionError("future failed", err.get());
    }
  }
}
