package com.github.moxib.pomelo.seqsvr.alloc;

import com.github.moxib.pomelo.seqsvr.mediate.MediateVerticle;
import com.github.moxib.pomelo.seqsvr.proto.RangeId;
import com.github.moxib.pomelo.seqsvr.proto.Router;
import com.github.moxib.pomelo.seqsvr.proto.RouterNode;
import com.github.moxib.pomelo.seqsvr.proto.SeqSvrConstants;
import com.github.moxib.pomelo.seqsvr.rpc.MediateClient;
import com.github.moxib.pomelo.seqsvr.rpc.SeqSvrAddresses;
import com.github.moxib.pomelo.seqsvr.store.StoreVerticle;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3 集成测试：单进程部署 StoreSvr + MediateSvr + 两个 AllocSvr（node-1 / node-2），
 * 验证：
 * <ul>
 *   <li>Mediate 注册后生成正确路由表（2 节点号段不重叠、覆盖全部分区）</li>
 *   <li>AllocSvr 进入 waitForRouter 模式，只服务自己分到的号段</li>
 *   <li>客户端按路由表把请求路由到拥有该号段的节点</li>
 * </ul>
 */
@DisplayName("Phase3 Mediate 仲裁 + 多节点 Alloc 集成测试")
class SeqSvrMediateIntegrationTest {

  private static final int MAX = SeqSvrConstants.DEBUG_MAX_ID_SIZE;

  private Vertx vertx;
  private String tempDir;

  @BeforeEach
  void setUp() throws Exception {
    tempDir = Files.createTempDirectory("seqsvr-mediate-it-").toString();
    System.setProperty("seqsvr.dataDir", tempDir);
    System.setProperty("seqsvr.setIdBegin", "0");
    System.setProperty("seqsvr.setIdSize", String.valueOf(MAX));
    // admin HTTP 随机端口，避免与本机 10106 冲突
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

  private static SeqAllocConfig allocConfig(String nodeId) {
    // syncLease/checkLease 200ms：让两个节点快速收敛到 Mediate 最终路由表
    return new SeqAllocConfig(MAX, 0, MAX, nodeId, "127.0.0.1", 0,
      SeqSvrAddresses.STORE_PREFIX, true, SeqSvrAddresses.MEDIATE_PREFIX,
      1000, 200, 200, 5000);
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
      throw new AssertionError("future failed", err.get());
    }
    return ref.get();
  }

  /** 按路由表找到 id 的归属节点 */
  private static String owningNode(Router router, int id) {
    for (RouterNode node : router.getNodeList()) {
      for (RangeId range : node.getSectionRanges()) {
        if (range.calcSectionID(id).isFound()) {
          return node.getNodeId();
        }
      }
    }
    return null;
  }

  /** 向指定节点地址发 fetchNext */
  private Future<JsonObject> fetch(String nodeId, int id, int version) {
    JsonObject req = new JsonObject().put("id", id).put("version", version);
    return vertx.eventBus().<JsonObject>request(SeqSvrAddresses.allocNodeFetchNext(nodeId), req)
      .map(msg -> msg.body());
  }

  @Test
  @DisplayName("Mediate 生成 2 节点路由表，客户端按号段路由，错误节点被拒")
  void mediateRoutesToOwningNode() throws Exception {
    // 部署 Store → Mediate → node-1 → node-2
    CountDownLatch deployed = new CountDownLatch(1);
    AtomicReference<Throwable> deployErr = new AtomicReference<>();
    vertx.deployVerticle(new StoreVerticle())
      .compose(id -> vertx.deployVerticle(new MediateVerticle()))
      .compose(id -> vertx.deployVerticle(new SeqAllocVerticle(allocConfig("node-1"))))
      .compose(id -> vertx.deployVerticle(new SeqAllocVerticle(allocConfig("node-2"))))
      .onSuccess(id -> deployed.countDown())
      .onFailure(err -> {
        deployErr.set(err);
        deployed.countDown();
      });
    assertTrue(deployed.await(15, TimeUnit.SECONDS), "部署超时");
    assertNull(deployErr.get(), "部署失败: " + deployErr);

    // 等待两节点租约收敛到最终路由表
    Thread.sleep(1500);

    // 1. 路由表：2 节点，version>=1，号段不重叠且覆盖全部分区
    Router router = await(new MediateClient(vertx.eventBus()).getRouter());
    assertEquals(2, router.getNodeList().size(), "应有 2 个 AllocSvr");
    assertTrue(router.getVersion() >= 1, "路由表版本应 >= 1");

    java.util.Set<String> nodeIds = new java.util.HashSet<>();
    int totalSections = 0;
    for (RouterNode node : router.getNodeList()) {
      assertTrue(nodeIds.add(node.getNodeId()), "nodeId 不应重复");
      int sections = 0;
      for (RangeId range : node.getSectionRanges()) {
        sections += range.calcSetSectionSize();
      }
      totalSections += sections;
      assertNotNull(node.getIp(), "节点应带 ip");
    }
    int expectedSections = new RangeId(0, MAX).calcSetSectionSize();
    assertEquals(expectedSections, totalSections, "路由表应覆盖全部分区");

    // 2. 客户端按路由表路由：选两个 id，一个在 node-1 段，一个在 node-2 段
    int[] testIds = {5, 500000, 600000, 900000};
    for (int id : testIds) {
      String owner = owningNode(router, id);
      assertNotNull(owner, "id " + id + " 应有归属节点");

      // 正向：发到归属节点成功且递增
      JsonObject resp1 = await(fetch(owner, id, 0));
      assertTrue(resp1.getLong("seq") > 0, "id " + id + " 正向应成功");
      JsonObject resp2 = await(fetch(owner, id, 0));
      assertTrue(resp2.getLong("seq") > resp1.getLong("seq"),
        "id " + id + " 应递增: " + resp1.getLong("seq") + " -> " + resp2.getLong("seq"));

      // 反向：发到非归属节点应返回 ROUTE_OUTDATED + 最新路由表（成功回复，客户端据此收敛）
      String wrong = nodeIds.stream().filter(n -> !n.equals(owner)).findFirst().orElseThrow();
      AtomicReference<JsonObject> wrongResp = new AtomicReference<>();
      AtomicReference<Throwable> wrongErr = new AtomicReference<>();
      CountDownLatch done = new CountDownLatch(1);
      fetch(wrong, id, 0).onComplete(ar -> {
        if (ar.succeeded()) wrongResp.set(ar.result());
        else wrongErr.set(ar.cause());
        done.countDown();
      });
      assertTrue(done.await(10, TimeUnit.SECONDS), "错误节点应快速返回");
      assertNull(wrongErr.get(), "错误节点应成功回复而非失败: " + wrongErr);
      assertEquals(SeqSvrConstants.ALLOC_CODE_ROUTE_OUTDATED, wrongResp.get().getInteger("code"),
        "id " + id + " 发到非归属节点应返回 ROUTE_OUTDATED");
      assertNotNull(wrongResp.get().getJsonObject("router"), "应携带最新路由表");
    }
  }
}
