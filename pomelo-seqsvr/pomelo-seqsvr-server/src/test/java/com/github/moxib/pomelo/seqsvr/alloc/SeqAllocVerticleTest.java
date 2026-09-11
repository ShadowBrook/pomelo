package com.github.moxib.pomelo.seqsvr.alloc;

import com.github.moxib.pomelo.seqsvr.proto.SeqSvrConstants;
import com.github.moxib.pomelo.seqsvr.rpc.SeqSvrAddresses;
import com.github.moxib.pomelo.seqsvr.store.StoreVerticle;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SeqAllocVerticle 集成测试：同进程部署 StoreVerticle + SeqAllocVerticle，
 * 走真实 EventBus RPC 路径（EventBusStoreClient → StoreVerticle），验证大 id 全链路可分配。
 * <p>
 * 对应运行时报错场景：id=1929383936（snowflake 用户 id 低 32 位）因超出默认 1MB id 空间
 * 抛出 "not found in any section range"。稀疏存储 + 生产默认 id 空间下应分配成功。
 */
@DisplayName("SeqAllocVerticle EventBus 分配链路测试")
class SeqAllocVerticleTest {

  private Vertx vertx;
  private String tempDir;

  @BeforeEach
  void setUp() throws IOException {
    tempDir = Files.createTempDirectory("seqsvr-verticle-test-").toString();
    // ConfigHolder 未加载时，seqsvr.dataDir 走系统属性 fallback，避免写入仓库 data/ 目录
    System.setProperty("seqsvr.dataDir", tempDir);
    vertx = Vertx.vertx();
  }

  @AfterEach
  void tearDown() {
    System.clearProperty("seqsvr.dataDir");
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
  @DisplayName("大 id 通过 EventBus 全链路可分配且不递增查询一致")
  void testLargeIdViaEventBus() throws Exception {
    // 先部署 StoreSvr，再部署 AllocSvr（init 需从 Store 加载 max_seqs）
    CountDownLatch deployed = new CountDownLatch(1);
    AtomicReference<Throwable> deployErr = new AtomicReference<>();
    vertx.deployVerticle(new StoreVerticle())
      .compose(storeId -> vertx.deployVerticle(new SeqAllocVerticle()))
      .onSuccess(id -> deployed.countDown())
      .onFailure(err -> {
        deployErr.set(err);
        deployed.countDown();
      });
    assertTrue(deployed.await(10, TimeUnit.SECONDS), "verticle 应成功部署");
    assertNull(deployErr.get(), "部署失败: " + deployErr);

    int bigId = 1929383936;
    JsonObject req = new JsonObject().put("id", bigId).put("version", 0);

    // fetchNextSequence
    CountDownLatch fetched = new CountDownLatch(1);
    AtomicReference<Long> seqRef = new AtomicReference<>();
    AtomicReference<Throwable> fetchErr = new AtomicReference<>();
    vertx.eventBus().<JsonObject>request("seqsvr.alloc.fetchNext", req)
      .onSuccess(resp -> {
        seqRef.set(resp.body().getLong("seq"));
        fetched.countDown();
      })
      .onFailure(err -> {
        fetchErr.set(err);
        fetched.countDown();
      });
    assertTrue(fetched.await(10, TimeUnit.SECONDS), "fetchNext 应返回结果");
    assertNull(fetchErr.get(), "fetchNext 失败: " + fetchErr);
    assertNotNull(seqRef.get(), "响应应携带 seq");
    assertTrue(seqRef.get() > 0, "seq 应为正数，实际 " + seqRef.get());

    // getCurrentSequence 不递增
    CountDownLatch curDone = new CountDownLatch(1);
    AtomicReference<Long> curRef = new AtomicReference<>();
    AtomicReference<Throwable> curErr = new AtomicReference<>();
    vertx.eventBus().<JsonObject>request("seqsvr.alloc.getCurrent", req)
      .onSuccess(resp -> {
        curRef.set(resp.body().getLong("seq"));
        curDone.countDown();
      })
      .onFailure(err -> {
        curErr.set(err);
        curDone.countDown();
      });
    assertTrue(curDone.await(10, TimeUnit.SECONDS), "getCurrent 应返回结果");
    assertNull(curErr.get(), "getCurrent 失败: " + curErr);
    assertEquals(seqRef.get(), curRef.get(), "getCurrent 应与上次分配的 seq 一致");
  }

  @Test
  @DisplayName("按 nodeId 路由地址同样可分配")
  void testNodeScopedAddress() throws Exception {
    CountDownLatch deployed = new CountDownLatch(1);
    AtomicReference<Throwable> deployErr = new AtomicReference<>();
    vertx.deployVerticle(new StoreVerticle())
      .compose(storeId -> vertx.deployVerticle(new SeqAllocVerticle()))
      .onSuccess(id -> deployed.countDown())
      .onFailure(err -> {
        deployErr.set(err);
        deployed.countDown();
      });
    assertTrue(deployed.await(10, TimeUnit.SECONDS), "verticle 应成功部署");
    assertNull(deployErr.get(), "部署失败: " + deployErr);

    // 默认 nodeId=node-1，走 seqsvr.alloc.node-1.fetchNext
    JsonObject req = new JsonObject().put("id", 12345).put("version", 0);
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<Long> seqRef = new AtomicReference<>();
    AtomicReference<Throwable> errRef = new AtomicReference<>();
    vertx.eventBus().<JsonObject>request("seqsvr.alloc.node-1.fetchNext", req)
      .onSuccess(resp -> {
        seqRef.set(resp.body().getLong("seq"));
        done.countDown();
      })
      .onFailure(err -> {
        errRef.set(err);
        done.countDown();
      });
    assertTrue(done.await(10, TimeUnit.SECONDS), "nodeScoped fetch 应返回结果");
    assertNull(errRef.get(), "nodeScoped fetch 失败: " + errRef);
    assertNotNull(seqRef.get(), "响应应携带 seq");
  }

  @Test
  @DisplayName("路由过期回复携带 retryAfterMs（客户端据此延迟重试，2026-09-07 事故 P9）")
  void testRouteOutdatedReplyCarriesRetryAfterMs() throws Exception {
    CountDownLatch deployed = new CountDownLatch(1);
    AtomicReference<Throwable> deployErr = new AtomicReference<>();
    // 节点只声明 [0,5000)，请求 id=9999 → 不在服务范围 → ROUTE_OUTDATED
    SeqAllocConfig config = new SeqAllocConfig(10000, 0, 5000, "node-1", "127.0.0.1", 0,
      "seqsvr.store", false, SeqSvrAddresses.MEDIATE_PREFIX,
      1000, AllocManager.SYNC_LEASE_TIMEOUT_MS, AllocManager.CHECK_LEASE_TIMEOUT_MS,
      AllocManager.LEASE_TIMEOUT_MS);
    vertx.deployVerticle(new StoreVerticle())
      .compose(storeId -> vertx.deployVerticle(new SeqAllocVerticle(config)))
      .onSuccess(id -> deployed.countDown())
      .onFailure(err -> {
        deployErr.set(err);
        deployed.countDown();
      });
    assertTrue(deployed.await(10, TimeUnit.SECONDS), "verticle 应成功部署");
    assertNull(deployErr.get(), "部署失败: " + deployErr);

    JsonObject req = new JsonObject().put("id", 9999).put("version", 0);
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<JsonObject> bodyRef = new AtomicReference<>();
    vertx.eventBus().<JsonObject>request("seqsvr.alloc.fetchNext", req)
      .onSuccess(resp -> {
        bodyRef.set(resp.body());
        done.countDown();
      })
      .onFailure(err -> done.countDown());
    assertTrue(done.await(10, TimeUnit.SECONDS), "请求应返回");
    assertNotNull(bodyRef.get(), "应收到 ROUTE_OUTDATED 回复而非失败");
    assertEquals(SeqSvrConstants.ALLOC_CODE_ROUTE_OUTDATED, bodyRef.get().getInteger("code"),
      "服务范围外的 id 应回复路由过期");
    assertEquals(2000L, bodyRef.get().getLong("retryAfterMs"), "过期回复应携带 retryAfterMs=2000");
  }

  @Test
  @DisplayName("admin 端口 /health：暴露 state/serving/routerVersion/subscriptionOk（事故 P5）")
  void testHealthEndpoint() throws Exception {
    int adminPort = 18105;
    CountDownLatch deployed = new CountDownLatch(1);
    AtomicReference<Throwable> deployErr = new AtomicReference<>();
    SeqAllocConfig config = new SeqAllocConfig(SeqSvrConstants.DEBUG_MAX_ID_SIZE, 0,
      SeqSvrConstants.DEBUG_MAX_ID_SIZE, "node-1", "127.0.0.1", 0,
      "seqsvr.store", false, SeqSvrAddresses.MEDIATE_PREFIX,
      1000, AllocManager.SYNC_LEASE_TIMEOUT_MS, AllocManager.CHECK_LEASE_TIMEOUT_MS,
      AllocManager.LEASE_TIMEOUT_MS, "", 2, 2, adminPort);
    vertx.deployVerticle(new StoreVerticle())
      .compose(storeId -> vertx.deployVerticle(new SeqAllocVerticle(config)))
      .onSuccess(id -> deployed.countDown())
      .onFailure(err -> {
        deployErr.set(err);
        deployed.countDown();
      });
    assertTrue(deployed.await(10, TimeUnit.SECONDS), "verticle 应成功部署");
    assertNull(deployErr.get(), "部署失败: " + deployErr);

    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<Integer> statusRef = new AtomicReference<>();
    AtomicReference<JsonObject> bodyRef = new AtomicReference<>();
    // 持有 HttpClient 强引用：Vert.x 5 会在客户端被 GC 时关闭其连接，
    // 随用随弃会让请求中途失败（表现为偶发 statusCode=null）
    HttpClient httpClient = vertx.createHttpClient();
    httpClient.request(io.vertx.core.http.HttpMethod.GET, adminPort, "127.0.0.1", "/health")
      .compose(req -> req.send())
      .onComplete(ar -> {
        if (ar.succeeded()) {
          HttpClientResponse resp = ar.result();
          statusRef.set(resp.statusCode());
          resp.body().onComplete(b -> {
            if (b.succeeded()) {
              bodyRef.set(io.vertx.core.json.Json.decodeValue(b.result(), JsonObject.class));
            }
            done.countDown();
          });
        } else {
          done.countDown();
        }
      });
    assertTrue(done.await(10, TimeUnit.SECONDS), "健康检查应返回");
    assertEquals(200, statusRef.get(), "INITED + 订阅可见应返回 200");
    assertEquals("INITED", bodyRef.get().getString("state"));
    assertEquals(Boolean.TRUE, bodyRef.get().getBoolean("serving"));
    assertEquals("node-1", bodyRef.get().getString("nodeId"));
    assertEquals(Boolean.TRUE, bodyRef.get().getBoolean("subscriptionOk"), "非集群测试环境订阅视为可见");
  }
}
