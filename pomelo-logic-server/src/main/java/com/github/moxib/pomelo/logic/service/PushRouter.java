package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.config.SessionRouteTable;
import com.github.moxib.pomelo.model.PushCodec;
import com.github.moxib.pomelo.model.PushEnvelope;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logic-Server 推送路由器。
 * 查询 SessionRouteTable 精确路由到目标 Gateway 节点，
 * 查不到时 fallback 到 publish 广播。
 */
public class PushRouter {

  private static final Logger LOG = LoggerFactory.getLogger(PushRouter.class);

  private final Vertx vertx;
  private final SessionRouteTable routeTable;

  public PushRouter(Vertx vertx) {
    this.vertx = vertx;
    this.routeTable = new SessionRouteTable(vertx);
  }

  /**
   * 推送消息。优先精确路由，失败则广播兜底。
   */
  public void push(PushEnvelope env) {
    String targetUserId = env.getTargetUserId();
    routeTable.resolve(targetUserId)
      .onSuccess(nodeId -> {
        if (nodeId != null && !nodeId.isEmpty()) {
          // 精确路由到目标 Gateway 节点
          String addr = "gateway.push." + nodeId;
          vertx.eventBus().send(addr, PushCodec.encode(env));
          LOG.debug("Push sent directly: target={} node={} cmd={}", targetUserId, nodeId, env.getCmd());
        } else {
          // 查不到路由，广播兜底
          vertx.eventBus().publish("gateway.push", PushCodec.encode(env));
          LOG.debug("Push broadcast (fallback): target={} cmd={}", targetUserId, env.getCmd());
        }
      })
      .onFailure(e -> {
        LOG.warn("Route lookup failed for {}, falling back to broadcast", targetUserId, e);
        vertx.eventBus().publish("gateway.push", PushCodec.encode(env));
      });
  }
}
