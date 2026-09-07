package com.github.moxib.pomelo.seqsvr.alloc;

import com.github.moxib.pomelo.seqsvr.proto.AllocState;
import com.github.moxib.pomelo.seqsvr.proto.RangeId;
import com.github.moxib.pomelo.seqsvr.proto.Router;
import com.github.moxib.pomelo.seqsvr.proto.RouterNode;
import com.github.moxib.pomelo.seqsvr.proto.SeqSvrConstants;
import com.github.moxib.pomelo.seqsvr.proto.Sequence;
import com.github.moxib.pomelo.seqsvr.rpc.MediateClient;
import com.github.moxib.pomelo.seqsvr.rpc.SeqSvrAddresses;
import com.github.moxib.pomelo.seqsvr.rpc.StoreAccessor;
import com.github.moxib.pomelo.seqsvr.rpc.StoreClients;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;

/**
 * AllocSvr Verticle — 序列号分配服务。
 * <p>
 * 存储层通过 {@link EventBusStoreClient} 访问独立 StoreSvr（跨进程 / Redis 集群 EventBus）。
 * 暴露 EventBus 接口：
 * <ul>
 *   <li>{@code seqsvr.alloc.fetchNext} / {@code seqsvr.alloc.getCurrent} — 兼容 / 无路由表兜底地址</li>
 *   <li>{@code seqsvr.alloc.<nodeId>.fetchNext} / {@code seqsvr.alloc.<nodeId>.getCurrent} — 客户端按路由表路由</li>
 * </ul>
 * <p>
 * 可选集成 MediateSvr（{@code mediateEnabled}）：启动时注册声明号段，周期心跳；
 * 路由表由 Mediate 分配（AllocManager 进入 waitForRouter 模式，不自举单节点）。
 */
public class SeqAllocVerticle extends VerticleBase {

  private static final Logger LOG = LoggerFactory.getLogger(SeqAllocVerticle.class);

  private final SeqAllocConfig config;

  private AllocManager allocManager;
  private MediateClient mediate;
  private long syncLeaseTimer;
  private long checkLeaseTimer;
  private long heartbeatTimer = -1;

  private RouterNode myNode;
  private int heartbeatFailures = 0;
  private boolean registering = false;

  public SeqAllocVerticle() {
    this.config = SeqAllocConfig.fromConfig();
  }

  public SeqAllocVerticle(SeqAllocConfig config) {
    this.config = config;
  }

  @Override
  public Future<?> start() {
    String nodeId = config.nodeId();

    RangeId setId = new RangeId(config.setIdBegin(), config.setIdSize());
    RangeId fullRange = new RangeId(0, config.maxIdSize());
    RouterNode myNode = new RouterNode(nodeId, config.ip(), config.port(),
      Collections.singletonList(fullRange));
    this.myNode = myNode;

    StoreAccessor store = StoreClients.create(vertx.eventBus(), config.storePrefix(),
      config.storeReplicas(), config.storeW(), config.storeR());
    allocManager = new AllocManager(store, setId, myNode, config.maxIdSize(), config.leaseMs(),
      config.mediateEnabled());

    // 注册 EventBus consumer
    registerConsumers(nodeId);

    // 租约定时器（与 EventBus consumer 同 context，线程安全）
    syncLeaseTimer = vertx.setPeriodic(config.syncLeaseMs(), id -> allocManager.syncLease());
    checkLeaseTimer = vertx.setPeriodic(config.checkLeaseMs(), id -> allocManager.checkLease(System.currentTimeMillis()));

    // 异步初始化：加载路由表 + maxSeqs → INITED
    Future<Void> initFuture = allocManager.init();

    if (!config.mediateEnabled()) {
      return initFuture
        .onSuccess(v -> LOG.info("SeqAllocVerticle started: nodeId={}, setId=[{},{}), maxIdSize={}, state={}",
          nodeId, config.setIdBegin(), config.setIdSize(), config.maxIdSize(), allocManager.getState()))
        .onFailure(err -> LOG.error("SeqAllocVerticle init failed: nodeId={}", nodeId, err));
    }

    // Mediate 模式：周期心跳 + 注册（失败自动重试，返回路由表立即应用）
    mediate = new MediateClient(vertx.eventBus(), config.mediatePrefix());
    heartbeatTimer = vertx.setPeriodic(config.heartbeatMs(), id -> heartbeat());

    return initFuture
      .onSuccess(v -> {
        ensureRegistered();
        LOG.info("SeqAllocVerticle start initiated: nodeId={}, state={}",
          nodeId, allocManager.getState());
      })
      .map(v -> null)
      .onFailure(err -> LOG.error("SeqAllocVerticle init failed: nodeId={}, cause={}",
        nodeId, err.getMessage()));
  }

  /** 注册失败后的重试间隔 */
  private static final long REGISTER_RETRY_MS = 5000;

  /** 心跳连续失败达到该次数即触发重新注册 */
  private static final int HEARTBEAT_FAILURE_THRESHOLD = 3;

  /**
   * 周期心跳。失败或 Mediate 认为本节点未知（重启后丢失）达到阈值时触发重新注册。
   */
  private void heartbeat() {
    mediate.heartbeat(config.nodeId(), new JsonObject())
      .onSuccess(ok -> {
        if (Boolean.TRUE.equals(ok)) {
          heartbeatFailures = 0;
        } else {
          onHeartbeatLost("mediate 认为本节点未知（可能已重启）");
        }
      })
      .onFailure(err -> onHeartbeatLost("mediate heartbeat 失败"));
  }

  private void onHeartbeatLost(String reason) {
    heartbeatFailures++;
    if (heartbeatFailures >= HEARTBEAT_FAILURE_THRESHOLD) {
      heartbeatFailures = 0;
      LOG.warn("{}，触发重新注册: nodeId={}", reason, config.nodeId());
      ensureRegistered();
    }
  }

  /**
   * 确保本节点已注册到 Mediate。已有注册流程进行中时直接返回，避免并发重复注册。
   */
  private void ensureRegistered() {
    if (mediate == null || registering) {
      return;
    }
    registering = true;
    registerWithMediateWithRetry(0);
  }

  /**
   * 注册到 Mediate；失败后定时重试。注册成功返回的路由表立即应用（无需等 4s 租约同步）。
   */
  private void registerWithMediateWithRetry(int attempt) {
    mediate.register(myNode)
      .onSuccess(router -> {
        registering = false;
        heartbeatFailures = 0;
        allocManager.updateRouter(router);
        LOG.info("SeqAllocVerticle registered with Mediate: nodeId={}, routerVersion={}, state={}",
          config.nodeId(), router.getVersion(), allocManager.getState());
      })
      .onFailure(err -> {
        LOG.warn("register with Mediate failed (attempt {}), retry in {}ms: nodeId={}, cause={}",
          attempt + 1, REGISTER_RETRY_MS, config.nodeId(), err.getMessage());
        vertx.setTimer(REGISTER_RETRY_MS, id -> registerWithMediateWithRetry(attempt + 1));
      });
  }

  private void registerConsumers(String nodeId) {
    // 兼容 / 兜底地址（单节点开发、客户端无路由表）
    vertx.eventBus().consumer(SeqSvrAddresses.ALLOC_FETCH_NEXT, this::onFetchNext);
    vertx.eventBus().consumer(SeqSvrAddresses.ALLOC_GET_CURRENT, this::onGetCurrent);
    // 按 nodeId 路由地址（多节点客户端凭路由表路由到拥有号段的节点）
    vertx.eventBus().consumer(SeqSvrAddresses.allocNodeFetchNext(nodeId), this::onFetchNext);
    vertx.eventBus().consumer(SeqSvrAddresses.allocNodeGetCurrent(nodeId), this::onGetCurrent);
  }

  private void onFetchNext(Message<Object> msg) {
    JsonObject body = (JsonObject) msg.body();
    int id = body.getInteger("id");
    int version = body.getInteger("version", 0);
    try {
      Sequence seq = allocManager.fetchNextSequence(id, version);
      msg.reply(buildResponse(seq));
    } catch (IllegalArgumentException e) {
      // 路由过期：本节点不拥有该号段 → 携带最新路由表回复，客户端据此收敛重试
      if (allocManager.getState() == AllocState.INITED && !allocManager.isServing(id)) {
        msg.reply(buildRouteOutdatedResponse(e.getMessage()));
      } else {
        LOG.warn("fetchNextSequence bad id: nodeId={}, id={}, cause={}", config.nodeId(), id, e.getMessage());
        msg.fail(400, e.getMessage());
      }
    } catch (Exception e) {
      LOG.warn("fetchNextSequence failed: nodeId={}, id={}, cause={}", config.nodeId(), id, e.getMessage());
      msg.fail(500, e.getMessage());
    }
  }

  private void onGetCurrent(Message<Object> msg) {
    JsonObject body = (JsonObject) msg.body();
    int id = body.getInteger("id");
    int version = body.getInteger("version", 0);
    try {
      Sequence seq = allocManager.getCurrentSequence(id, version);
      msg.reply(buildResponse(seq));
    } catch (IllegalArgumentException e) {
      // 路由过期：本节点不拥有该号段 → 携带最新路由表回复，客户端据此收敛重试
      if (allocManager.getState() == AllocState.INITED && !allocManager.isServing(id)) {
        msg.reply(buildRouteOutdatedResponse(e.getMessage()));
      } else {
        LOG.warn("getCurrentSequence bad id: nodeId={}, id={}, cause={}", config.nodeId(), id, e.getMessage());
        msg.fail(400, e.getMessage());
      }
    } catch (Exception e) {
      LOG.warn("getCurrentSequence failed: nodeId={}, id={}, cause={}", config.nodeId(), id, e.getMessage());
      msg.fail(500, e.getMessage());
    }
  }

  /**
   * 构建分配成功响应。
   * 若客户端路由表过期，将完整 Router 嵌入响应（嵌入式路由表机制）。
   */
  private JsonObject buildResponse(Sequence seq) {
    JsonObject resp = new JsonObject()
      .put("code", SeqSvrConstants.ALLOC_CODE_OK)
      .put("seq", seq.getSeq());
    if (seq.hasRouter()) {
      resp.put("routeVersion", seq.getRouter().getVersion());
      resp.put("router", JsonObject.mapFrom(seq.getRouter()));
    }
    return resp;
  }

  /** 路由过期回复的建议重试间隔：客户端据此延迟重试，避免立即耗尽重试次数（2026-09-07 事故 P9） */
  public static final long RETRY_AFTER_MS = 2000;

  /**
   * 构建"路由过期"响应：该节点不拥有请求 id 的号段，携带最新路由表供客户端收敛。
   */
  private JsonObject buildRouteOutdatedResponse(String message) {
    Router router = allocManager.getRouter();
    return new JsonObject()
      .put("code", SeqSvrConstants.ALLOC_CODE_ROUTE_OUTDATED)
      .put("message", message)
      .put("retryAfterMs", RETRY_AFTER_MS)
      .put("routeVersion", router.getVersion())
      .put("router", JsonObject.mapFrom(router));
  }

  @Override
  public Future<?> stop() {
    vertx.cancelTimer(syncLeaseTimer);
    vertx.cancelTimer(checkLeaseTimer);
    if (heartbeatTimer != -1) {
      vertx.cancelTimer(heartbeatTimer);
    }
    // 优雅下线：通知 Mediate 移除本节点，号段立即重排到其他节点
    if (mediate != null) {
      mediate.unregister(config.nodeId()).onComplete(ar -> {
        if (ar.failed()) {
          LOG.warn("unregister from Mediate failed: nodeId={}, cause={}", config.nodeId(), ar.cause().getMessage());
        }
      });
    }
    LOG.info("SeqAllocVerticle stopped: nodeId={}", config.nodeId());
    return Future.succeededFuture();
  }

  /** 供集成测试 / 诊断使用 */
  public AllocManager getAllocManager() {
    return allocManager;
  }
}
