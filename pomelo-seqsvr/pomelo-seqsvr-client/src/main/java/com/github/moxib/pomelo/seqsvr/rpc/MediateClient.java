package com.github.moxib.pomelo.seqsvr.rpc;

import com.github.moxib.pomelo.seqsvr.proto.Router;
import com.github.moxib.pomelo.seqsvr.proto.RouterNode;
import io.vertx.core.Future;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonObject;

/**
 * MediateSvr 的 EventBus RPC 客户端 — AllocSvr 注册 / 心跳 / 下线，控制面查询。
 */
public class MediateClient {

  private static final long REQUEST_TIMEOUT_MS = 10_000;

  private final EventBus eventBus;
  private final String address;

  public MediateClient(EventBus eventBus) {
    this(eventBus, SeqSvrAddresses.MEDIATE_PREFIX);
  }

  public MediateClient(EventBus eventBus, String prefix) {
    this.eventBus = eventBus;
    this.address = prefix;
  }

  /**
   * 注册本节点，返回 Mediate 生成的当前路由表（便于立即应用，无需等 4s 租约同步）。
   */
  public Future<Router> register(RouterNode node) {
    return eventBus.request(address + ".registerAllocSvr", JsonObject.mapFrom(node),
        new DeliveryOptions().setSendTimeout(REQUEST_TIMEOUT_MS))
      .map(msg -> ((JsonObject) msg.body()).getJsonObject("router").mapTo(Router.class));
  }

  /**
   * 优雅下线。
   */
  public Future<Boolean> unregister(String nodeId) {
    return eventBus.request(address + ".unRegisterAllocSvr", new JsonObject().put("nodeId", nodeId),
        new DeliveryOptions().setSendTimeout(REQUEST_TIMEOUT_MS))
      .map(msg -> ((JsonObject) msg.body()).getBoolean("ok", false));
  }

  /**
   * 心跳上报（nodeId + 负载信息）。
   */
  public Future<Boolean> heartbeat(String nodeId, JsonObject load) {
    JsonObject req = new JsonObject().put("nodeId", nodeId).put("load", load);
    return eventBus.request(address + ".heartbeat", req,
        new DeliveryOptions().setSendTimeout(REQUEST_TIMEOUT_MS))
      .map(msg -> ((JsonObject) msg.body()).getBoolean("ok", false));
  }

  /**
   * 查询当前路由表（admin / 客户端冷启动）。
   */
  public Future<Router> getRouter() {
    return eventBus.request(address + ".getRouter", new JsonObject(),
        new DeliveryOptions().setSendTimeout(REQUEST_TIMEOUT_MS))
      .map(msg -> ((JsonObject) msg.body()).getJsonObject("router").mapTo(Router.class));
  }
}
