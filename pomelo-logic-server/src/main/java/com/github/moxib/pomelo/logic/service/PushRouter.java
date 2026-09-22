package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.config.SessionRouteTable;
import com.github.moxib.pomelo.metrics.PomeloMetrics;
import com.github.moxib.pomelo.model.PushCodec;
import com.github.moxib.pomelo.model.PushEnvelope;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Logic-Server 推送路由器。
 * 集群模式按 SessionRouteTable 解析用户全部端型路由并逐一投递（web/android 各端
 * 同步收信）；目标端不在线（无路由）或所在节点已死时直接丢弃——推送只是降低在线
 * 收信延迟的优化，消息可靠性由 seq + ACK + PULL 离线同步保证，不做广播兜底。
 * 非集群单进程模式下经本地 EventBus 点对点投递给本进程 Gateway（信封不带端型，
 * Gateway 投递给该用户在本节点的全部端会话）。
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
   * 推送消息（按端型扇出）。集群模式对用户的每个在线端型各投递一次（复制信封携带端型）；
   * 单进程模式本地投递一次。mode 标签：precise / dropped / local。
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
    routeTable.resolveAll(targetUserId)
      .onSuccess(routes -> {
        if (routes.isEmpty()) {
          PomeloMetrics.counter("im.push.delivery.total", "mode", "dropped", "reason", "no_route").increment();
          LOG.debug("Push dropped (no_route): target={} cmd={}", targetUserId, env.getCmd());
          return;
        }
        for (Map.Entry<String, String> route : routes.entrySet()) {
          deliverToNode(env, route.getKey(), route.getValue(), targetUserId);
        }
      })
      .onFailure(e -> {
        LOG.warn("Route lookup failed for {}, push dropped (由客户端离线 PULL 补偿)", targetUserId, e);
        PomeloMetrics.counter("im.push.delivery.total", "mode", "dropped", "reason", "lookup_failed").increment();
      });
  }

  /**
   * 投递到单个端型所在节点。路由指向的节点已死（gateway 崩溃）时连接随节点消亡，
   * 用户在该端型上必然离线：条件清理残留路由并丢弃。
   */
  private void deliverToNode(PushEnvelope env, String platform, String nodeId, String targetUserId) {
    routeTable.isNodeAlive(nodeId).onSuccess(alive -> {
      if (alive) {
        // 精确路由到存活的 Gateway 节点（信封复制并携带端型，gateway 据此选会话）
        PomeloMetrics.counter("im.push.delivery.total", "mode", "precise", "reason", "none").increment();
        String addr = "gateway.push." + nodeId;
        vertx.eventBus().send(addr, PushCodec.encode(env.forPlatform(platform)));
        LOG.debug("Push sent: target={} platform={} node={} cmd={}",
          targetUserId, platform, nodeId, env.getCmd());
      } else {
        // 仅当路由仍指向该死节点时清理，避免误删用户刚迁移到新节点的路由
        routeTable.unregister(targetUserId, platform, nodeId);
        PomeloMetrics.counter("im.push.delivery.total", "mode", "dropped", "reason", "dead_node").increment();
        LOG.debug("Push dropped (dead_node): target={} platform={} cmd={}",
          targetUserId, platform, env.getCmd());
      }
    });
  }
}
