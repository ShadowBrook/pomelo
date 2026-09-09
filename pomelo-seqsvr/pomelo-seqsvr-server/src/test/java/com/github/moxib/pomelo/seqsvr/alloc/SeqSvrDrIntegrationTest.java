package com.github.moxib.pomelo.seqsvr.alloc;

import com.github.moxib.pomelo.seqsvr.client.SeqClientService;
import com.github.moxib.pomelo.seqsvr.mediate.MediateVerticle;
import com.github.moxib.pomelo.seqsvr.proto.SeqSvrConstants;
import com.github.moxib.pomelo.seqsvr.rpc.MediateClient;
import com.github.moxib.pomelo.seqsvr.rpc.SeqSvrAddresses;
import com.github.moxib.pomelo.seqsvr.store.StoreVerticle;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4 容灾演练测试（单进程模拟）：
 * <ol>
 *   <li>部署 Store + Mediate + node-1 + node-2，客户端收敛到 2 节点路由表</li>
 *   <li>下线 node-1（等价 kill：优雅 unregister，Mediate 立即重排路由表）</li>
 *   <li>node-2 接管全部分区（pending 延迟激活后）</li>
 *   <li>客户端继续发号：路由过期 / 目标不可达时自动重试收敛，sequence 单调不回退</li>
 * </ol>
 */
@DisplayName("Phase4 多节点容灾 + 客户端重试测试")
class SeqSvrDrIntegrationTest {

  private static final int MAX = SeqSvrConstants.DEBUG_MAX_ID_SIZE;

  private Vertx vertx;
  private String tempDir;
  private String node1DeploymentId;

  @BeforeEach
  void setUp() throws Exception {
    tempDir = Files.createTempDirectory("seqsvr-dr-it-").toString();
    System.setProperty("seqsvr.dataDir", tempDir);
    System.setProperty("seqsvr.setIdBegin", "0");
    System.setProperty("seqsvr.setIdSize", String.valueOf(MAX));
    System.setProperty("seqsvr.mediate.adminPort", "0");
    vertx = Vertx.vertx();
  }

  @AfterEach
  void tearDown() {
    System.clearProperty("seqsvr.dataDir");
    System.clearProperty("seqsvr.setIdBegin");
    System.clearProperty("seqsvr.setIdSize");
    System.clearProperty("seqsvr.mediate.adminPort");
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

  /** 租约 1s（pending 号段 1s 后激活），sync/check 200ms，让迁移快速收敛 */
  private static SeqAllocConfig allocConfig(String nodeId) {
    return new SeqAllocConfig(MAX, 0, MAX, nodeId, "127.0.0.1", 0,
      SeqSvrAddresses.STORE_PREFIX, true, SeqSvrAddresses.MEDIATE_PREFIX,
      1000, 200, 200, 1000);
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
    if (!latch.await(20, TimeUnit.SECONDS)) {
      throw new AssertionError("future timed out");
    }
    if (err.get() != null) {
      throw new AssertionError("future failed: " + err.get(), err.get());
    }
    return ref.get();
  }

  @Test
  @DisplayName("节点下线后客户端自动收敛，sequence 单调不回退")
  void clientSurvivesNodeFailureWithMonotonicSeq() throws Exception {
    // 部署 Store → Mediate → node-1 → node-2
    CountDownLatch deployed = new CountDownLatch(1);
    AtomicReference<Throwable> deployErr = new AtomicReference<>();
    vertx.deployVerticle(new StoreVerticle())
      .compose(id -> vertx.deployVerticle(new MediateVerticle()))
      .compose(id -> vertx.deployVerticle(new SeqAllocVerticle(allocConfig("node-1"))))
      .compose(id -> {
        node1DeploymentId = id;
        return vertx.deployVerticle(new SeqAllocVerticle(allocConfig("node-2")));
      })
      .onSuccess(id -> deployed.countDown())
      .onFailure(err -> {
        deployErr.set(err);
        deployed.countDown();
      });
    assertTrue(deployed.await(20, TimeUnit.SECONDS), "部署超时");
    assertNull(deployErr.get(), "部署失败: " + deployErr);

    // 等待两节点路由表收敛 + 客户端拿到 2 节点路由表
    Thread.sleep(2000);
    assertEquals(2, await(new MediateClient(vertx.eventBus()).getRouter()).getNodeList().size(),
      "Mediate 应有 2 个节点");

    // 迁移前：客户端收敛到 2 节点路由表，每 uid 拉取 3 次记录最后 seq
    // maxIdSize 与服务端对齐（DEBUG 空间），否则客户端哈希到全量空间会超出测试 set
    SeqClientService client = new SeqClientService(vertx, MAX);
    long[] userIds = {1L, 100L, 500000L, 123456789L};
    Map<Long, Long> lastSeqs = new HashMap<>();
    for (int round = 0; round < 3; round++) {
      for (long uid : userIds) {
        lastSeqs.put(uid, await(client.fetchNextSequence(uid)));
      }
    }
    int preVersion = client.getRouteVersion();
    assertEquals(2, client.getRouter().getNodeList().size(), "客户端应已收敛到 2 节点路由表");
    assertTrue(preVersion >= 1, "客户端路由表版本应 >= 1");

    // 迁移：下线 node-1
    CountDownLatch undeployed = new CountDownLatch(1);
    AtomicReference<Throwable> undepErr = new AtomicReference<>();
    vertx.undeploy(node1DeploymentId).onComplete(ar -> {
      if (ar.failed()) undepErr.set(ar.cause());
      undeployed.countDown();
    });
    assertTrue(undeployed.await(20, TimeUnit.SECONDS), "undeploy 超时");
    assertNull(undepErr.get(), "undeploy 失败: " + undepErr);

    // 等待 Mediate 重排路由表 + node-2 pending 号段激活（租约 1s）
    Thread.sleep(3000);

    // 迁移后：客户端继续发号，应成功且单调递增（不回退）
    for (long uid : userIds) {
      long seq = await(client.fetchNextSequence(uid));
      assertTrue(seq > lastSeqs.get(uid),
        String.format("uid %d 迁移后 seq 应递增: last=%d, now=%d", uid, lastSeqs.get(uid), seq));
    }
    // 客户端路由表已收敛到最新（单节点接管全部）
    assertTrue(client.getRouteVersion() > preVersion,
      "客户端路由表版本应提升: pre=" + preVersion + ", now=" + client.getRouteVersion());
    assertEquals(1, client.getRouter().getNodeList().size(),
      "客户端路由表应收敛为单节点（node-2 接管全部）");
  }
}
