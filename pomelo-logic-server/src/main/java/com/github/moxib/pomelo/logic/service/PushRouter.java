package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.config.SessionRouteTable;
import com.github.moxib.pomelo.metrics.PomeloMetrics;
import com.github.moxib.pomelo.model.PushCodec;
import com.github.moxib.pomelo.model.PushEnvelope;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Logic-Server 推送路由器。
 * 集群模式按 SessionRouteTable 精确路由到目标 Gateway 节点；目标不在线（无路由）
 * 或所在节点已死时直接丢弃——推送只是降低在线收信延迟的优化，消息可靠性由
 * seq + ACK + PULL 离线同步保证，不做广播兜底（离线用户占比高，广播是纯放大）。
 * 非集群单进程模式下经本地 EventBus 点对点投递给本进程 Gateway。
 */
public class PushRouter {

  private static final Logger LOG = LoggerFactory.getLogger(PushRouter.class);

  // 单进程（非集群）模式的推送地址，与 MessageDispatcher 的本地 push consumer 对应
  static final String LOCAL_PUSH_ADDRESS = "gateway.push";

  private final Vertx vertx;
  private final SessionRouteTable routeTable;

  public PushRouter(Vertx vertx) {
    this(vertx, new SessionRouteTable(vertx));
  }

  /** 显式注入路由表（供测试使用） */
  PushRouter(Vertx vertx, SessionRouteTable routeTable) {
    this.vertx = vertx;
    this.routeTable = routeTable;
  }

  /**
   * 推送消息。集群模式仅精确路由：目标在线投递，离线/死节点/路由查询失败丢弃并按原因计数；
   * 单进程模式本地投递。mode 标签：precise / dropped / local。
   * Prometheus 要求同名指标 tag key 一致，所有路径统一携带 reason（不适用时为 none）。
   */
  public void push(PushEnvelope env) {
    env.setSentAtEpochMs(System.currentTimeMillis());
    String targetUserId = env.getTargetUserId();
    if (!routeTable.isRoutingAvailable()) {
      PomeloMetrics.counter("im.push.delivery.total", "mode", "local", "reason", "none").increment();
      vertx.eventBus().send(LOCAL_PUSH_ADDRESS, PushCodec.encode(env));
      return;
    }
    routeTable.resolve(targetUserId)
      .compose(nodeId -> {
        if (nodeId == null || nodeId.isEmpty()) {
          return Future.succeededFuture(Map.entry("", false));
        }
        // 路由指向的节点已死（gateway 崩溃）时连接随节点消亡，用户必然离线
        return routeTable.isNodeAlive(nodeId).map(alive -> Map.entry(nodeId, alive));
      })
      .onSuccess(route -> {
        String nodeId = route.getKey();
        boolean alive = route.getValue();
        if (!nodeId.isEmpty() && alive) {
          // 精确路由到存活的 Gateway 节点
          PomeloMetrics.counter("im.push.delivery.total", "mode", "precise", "reason", "none").increment();
          String addr = "gateway.push." + nodeId;
          vertx.eventBus().send(addr, PushCodec.encode(env));
          LOG.debug("Push sent directly: target={} node={} cmd={}", targetUserId, nodeId, env.getCmd());
        } else {
          if (!nodeId.isEmpty()) {
            // 仅当路由仍指向该死节点时清理，避免误删用户刚迁移到新节点的路由
            routeTable.unregister(targetUserId, nodeId);
          }
          String reason = nodeId.isEmpty() ? "no_route" : "dead_node";
          PomeloMetrics.counter("im.push.delivery.total", "mode", "dropped", "reason", reason).increment();
          LOG.debug("Push dropped ({}): target={} cmd={}", reason, targetUserId, env.getCmd());
        }
      })
      .onFailure(e -> {
        LOG.warn("Route lookup failed for {}, push dropped (由客户端离线 PULL 补偿)", targetUserId, e);
        PomeloMetrics.counter("im.push.delivery.total", "mode", "dropped", "reason", "lookup_failed").increment();
      });
  }
}
