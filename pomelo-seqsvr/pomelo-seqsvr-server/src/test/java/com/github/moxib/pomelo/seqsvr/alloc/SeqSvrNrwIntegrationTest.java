package com.github.moxib.pomelo.seqsvr.alloc;

import com.github.moxib.pomelo.seqsvr.proto.SeqSvrConstants;
import com.github.moxib.pomelo.seqsvr.rpc.ReplicatedStoreClient;
import com.github.moxib.pomelo.seqsvr.store.StoreConfig;
import com.github.moxib.pomelo.seqsvr.store.StoreVerticle;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5 NRW 多副本测试：3 个 StoreSvr 副本 + ReplicatedStoreClient（W=2, R=2）。
 * <ul>
 *   <li>saveMaxSeq 写需 W 个副本确认，loadMaxSeqsData 从 R 个副本读并各 section 取 max</li>
 *   <li>停 1 个副本仍可读写（W/R=2 由剩余 2 副本满足）</li>
 *   <li>停 2 个副本后仲裁数不满足 → 读写失败</li>
 * </ul>
 */
@DisplayName("Phase5 NRW 多副本 Store 测试")
class SeqSvrNrwIntegrationTest {

  private static final int MAX = SeqSvrConstants.DEBUG_MAX_ID_SIZE;
  private static final List<String> REPLICAS = List.of(
    "seqsvr.store.r1", "seqsvr.store.r2", "seqsvr.store.r3");

  private Vertx vertx;
  private Path tempDir;
  private String[] replicaDeploymentIds = new String[3];

  @BeforeEach
  void setUp() throws Exception {
    tempDir = Files.createTempDirectory("seqsvr-nrw-it-");
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

  private static <T> T await(Future<T> future) throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<T> ref = new AtomicReference<>();
    AtomicReference<Throwable> err = new AtomicReference<>();
    future.onComplete(ar -> {
      if (ar.succeeded()) ref.set(ar.result());
      else err.set(ar.cause());
      latch.countDown();
    });
    if (!latch.await(15, TimeUnit.SECONDS)) {
      throw new AssertionError("future timed out");
    }
    if (err.get() != null) {
      throw new AssertionError("future failed: " + err.get(), err.get());
    }
    return ref.get();
  }

  private void deployReplica(int i) throws Exception {
    String rid = "r" + (i + 1);
    Path dir = tempDir.resolve(rid);
    Files.createDirectories(dir);
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<Throwable> err = new AtomicReference<>();
    vertx.deployVerticle(new StoreVerticle(new StoreConfig(dir.toString(), 0, MAX, rid)))
      .onSuccess(id -> {
        replicaDeploymentIds[i] = id;
        done.countDown();
      })
      .onFailure(e -> {
        err.set(e);
        done.countDown();
      });
    if (!done.await(15, TimeUnit.SECONDS)) {
      throw new AssertionError("deploy replica " + rid + " timed out");
    }
    if (err.get() != null) {
      throw new AssertionError("deploy replica " + rid + " failed", err.get());
    }
  }

  private void undeployReplica(int i) throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<Throwable> err = new AtomicReference<>();
    vertx.undeploy(replicaDeploymentIds[i]).onComplete(ar -> {
      if (ar.failed()) err.set(ar.cause());
      done.countDown();
    });
    if (!done.await(15, TimeUnit.SECONDS)) {
      throw new AssertionError("undeploy r" + (i + 1) + " timed out");
    }
    if (err.get() != null) {
      throw new AssertionError("undeploy r" + (i + 1) + " failed", err.get());
    }
  }

  @Test
  @DisplayName("3 副本 W=2 R=2：停 1 副本仍可读写，停 2 副本仲裁失败")
  void testNrwQuorumAndResilience() throws Exception {
    for (int i = 0; i < 3; i++) {
      deployReplica(i);
    }
    ReplicatedStoreClient client = new ReplicatedStoreClient(vertx.eventBus(), REPLICAS, 2, 2);

    // 3 副本全活：写读成功
    long v1 = await(client.saveMaxSeq(500, 500));
    assertEquals(10000L, v1, "首次写应对齐到 SEQ_STEP");
    long[] maxSeqs1 = await(client.loadMaxSeqsData());
    assertTrue(maxSeqs1[0] >= 10000L, "读应合并出各副本 max");

    // 停 r3：剩余 r1+r2 满足 W=2/R=2，仍可读写
    undeployReplica(2);
    long v2 = await(client.saveMaxSeq(501, 15000));
    assertEquals(20000L, v2, "写应在剩余副本上对齐持久化");
    long[] maxSeqs2 = await(client.loadMaxSeqsData());
    assertEquals(20000L, maxSeqs2[0], "读应取到 r1/r2 中 max=20000");

    // 停 r2：只剩 r1，W=2/R=2 不满足 → 读写失败
    undeployReplica(1);
    AtomicReference<Throwable> writeErr = new AtomicReference<>();
    CountDownLatch wl = new CountDownLatch(1);
    client.saveMaxSeq(502, 25000).onComplete(ar -> {
      if (ar.failed()) writeErr.set(ar.cause());
      wl.countDown();
    });
    assertTrue(wl.await(10, TimeUnit.SECONDS), "写应快速失败");
    assertNotNull(writeErr.get(), "只有 1 副本时应写失败（quorum 不足）");

    AtomicReference<Throwable> readErr = new AtomicReference<>();
    CountDownLatch rl = new CountDownLatch(1);
    client.loadMaxSeqsData().onComplete(ar -> {
      if (ar.failed()) readErr.set(ar.cause());
      rl.countDown();
    });
    assertTrue(rl.await(10, TimeUnit.SECONDS), "读应快速失败");
    assertNotNull(readErr.get(), "只有 1 副本时应读失败（quorum 不足）");
  }
}
