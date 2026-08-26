package com.github.moxib.pomelo.seqsvr.mediate;

import com.github.moxib.pomelo.config.ConfigHolder;
import com.github.moxib.pomelo.seqsvr.proto.RangeId;
import com.github.moxib.pomelo.seqsvr.proto.Router;
import com.github.moxib.pomelo.seqsvr.proto.RouterNode;
import com.github.moxib.pomelo.seqsvr.proto.SeqSvrConstants;
import com.github.moxib.pomelo.seqsvr.rpc.SeqSvrAddresses;
import com.github.moxib.pomelo.seqsvr.rpc.StoreAccessor;
import com.github.moxib.pomelo.seqsvr.rpc.StoreClients;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MediateSvr Verticle — 仲裁服务。
 * <p>
 * EventBus RPC：
 * <ul>
 *   <li>{@code seqsvr.mediate.registerAllocSvr} — 注册，返回新路由表</li>
 *   <li>{@code seqsvr.mediate.unRegisterAllocSvr} — 优雅下线</li>
 *   <li>{@code seqsvr.mediate.heartbeat} — 心跳探测</li>
 *   <li>{@code seqsvr.mediate.getRouter} — 查询路由表</li>
 * </ul>
 * Admin HTTP（端口 {@code seqsvr.mediate.adminPort}，默认 10106）：
 * <ul>
 *   <li>GET /router — 当前路由表</li>
 *   <li>GET /nodes — 节点存活状态</li>
 * </ul>
 */
public class MediateVerticle extends VerticleBase {

  private static final Logger LOG = LoggerFactory.getLogger(MediateVerticle.class);

  private MediateManager manager;
  private long timeoutCheckTimer;
  private HttpServer adminServer;

  @Override
  public Future<?> start() {
    int setIdBegin = ConfigHolder.getInt("seqsvr.setIdBegin", 0);
    int setIdSize = ConfigHolder.getInt("seqsvr.setIdSize", SeqSvrConstants.PRODUCTION_MAX_ID_SIZE);
    long heartbeatTimeoutMs = ConfigHolder.getLong("seqsvr.mediate.timeoutMs", MediateManager.HEARTBEAT_TIMEOUT_MS);
    long checkIntervalMs = ConfigHolder.getLong("seqsvr.mediate.checkIntervalMs", 1000);
    int adminPort = ConfigHolder.getInt("seqsvr.mediate.adminPort", 10106);
    String storePrefix = ConfigHolder.getString("seqsvr.store.address", SeqSvrAddresses.STORE_PREFIX);

    RangeId setId = new RangeId(setIdBegin, setIdSize);
    String storeReplicas = ConfigHolder.getString("seqsvr.store.replicas", "");
    int storeW = ConfigHolder.getInt("seqsvr.store.w", 2);
    int storeR = ConfigHolder.getInt("seqsvr.store.r", 2);
    StoreAccessor store = StoreClients.create(vertx.eventBus(), storePrefix, storeReplicas, storeW, storeR);
    manager = new MediateManager(store, setId, heartbeatTimeoutMs);

    return manager.init()
      .compose(v -> {
        vertx.eventBus().consumer(SeqSvrAddresses.MEDIATE_REGISTER, this::onRegister);
        vertx.eventBus().consumer(SeqSvrAddresses.MEDIATE_UNREGISTER, this::onUnregister);
        vertx.eventBus().consumer(SeqSvrAddresses.MEDIATE_HEARTBEAT, this::onHeartbeat);
        vertx.eventBus().consumer(SeqSvrAddresses.MEDIATE_GET_ROUTER, this::onGetRouter);

        timeoutCheckTimer = vertx.setPeriodic(checkIntervalMs, id -> manager.checkTimeouts(System.currentTimeMillis()));

        // Admin HTTP（无 vertx-web，手动路由两个只读端点）
        adminServer = vertx.createHttpServer();
        adminServer.requestHandler(req -> {
          String path = req.path();
          if ("/router".equals(path)) {
            req.response().putHeader("content-type", "application/json")
              .end(JsonObject.mapFrom(manager.getRouter()).encode());
          } else if ("/nodes".equals(path)) {
            req.response().putHeader("content-type", "application/json")
              .end(new JsonObject().put("nodes", manager.getNodeCount())
                .put("lastSeen", manager.getLastSeen()).encode());
          } else {
            req.response().setStatusCode(404).end();
          }
        });

        return adminServer.listen(adminPort);
      })
      .map(v -> null)
      .onSuccess(v -> LOG.info("MediateVerticle started: setId=[{},{}), sections={}, adminPort={}",
        setIdBegin, setIdSize, manager.getSectionCount(), adminPort))
      .onFailure(err -> LOG.error("MediateVerticle failed to start admin HTTP on port {}", adminPort, err));
  }

  private void onRegister(Message<Object> msg) {
    try {
      RouterNode node = ((JsonObject) msg.body()).mapTo(RouterNode.class);
      Router newRouter = manager.register(node);
      msg.reply(new JsonObject().put("ok", true).put("router", JsonObject.mapFrom(newRouter)));
    } catch (Exception e) {
      LOG.warn("registerAllocSvr failed: {}", e.getMessage());
      msg.fail(500, e.getMessage());
    }
  }

  private void onUnregister(Message<Object> msg) {
    String nodeId = ((JsonObject) msg.body()).getString("nodeId");
    Router newRouter = manager.unregister(nodeId);
    msg.reply(new JsonObject().put("ok", true).put("router", JsonObject.mapFrom(newRouter)));
  }

  private void onHeartbeat(Message<Object> msg) {
    JsonObject body = (JsonObject) msg.body();
    String nodeId = body.getString("nodeId");
    JsonObject load = body.getJsonObject("load", new JsonObject());
    boolean ok = manager.heartbeat(nodeId, load.getMap());
    msg.reply(new JsonObject().put("ok", ok));
  }

  private void onGetRouter(Message<Object> msg) {
    msg.reply(new JsonObject().put("router", JsonObject.mapFrom(manager.getRouter())));
  }

  @Override
  public Future<?> stop() {
    vertx.cancelTimer(timeoutCheckTimer);
    if (adminServer != null) {
      return adminServer.close().mapEmpty();
    }
    return Future.succeededFuture();
  }
}
