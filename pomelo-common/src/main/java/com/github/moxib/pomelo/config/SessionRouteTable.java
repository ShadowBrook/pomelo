package com.github.moxib.pomelo.config;

import io.github.shadowbrook.RedisClusterManager;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.shareddata.AsyncMap;
import io.vertx.core.spi.cluster.NodeInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.UUID;

/**
 * 用户 → Gateway 节点路由表（基于分布式 Map）。
 *
 * 节点身份直接复用 cluster manager 的 nodeId：节点存活由 CM 自身的 nodeInfo 目录
 * （TTL + 周期续期）统一维护，本类不再单独写存活标记、不再自建心跳。
 * Logic-Server 推送前查询目标节点 + 校验节点存活，精确路由到具体 Gateway；
 * 节点已死或查不到时由 PushRouter fallback 到 publish 广播。
 */
public final class SessionRouteTable {

  private static final Logger LOG = LoggerFactory.getLogger(SessionRouteTable.class);

  private static final String MAP_NAME = "__pomelo.session-routes";

  private final Vertx vertx;
  private final RedisClusterManager clusterManager;
  private final String nodeId;
  private volatile AsyncMap<String, String> map;

  /**
   * 默认构造：集群模式下复用 {@link ClusterHelper} 创建的 cluster manager 的节点身份。
   * 非集群模式或无 CM 时退化为随机 ID（路由表整体 no-op）。
   */
  public SessionRouteTable(Vertx vertx) {
    this(vertx, vertx.isClustered() ? ClusterHelper.clusterManager().orElse(null) : null);
  }

  /** 显式注入 cluster manager（供测试或自定义集群装配使用）。 */
  public SessionRouteTable(Vertx vertx, RedisClusterManager clusterManager) {
    this.vertx = vertx;
    this.clusterManager = clusterManager;
    String cmNodeId = clusterManager != null ? clusterManager.getNodeId() : null;
    this.nodeId = cmNodeId != null ? cmNodeId : UUID.randomUUID().toString();
  }

  /** 本 Gateway 节点 ID */
  public String getNodeId() {
    return nodeId;
  }

  /** 获取分布式 Map，lazy init */
  private Future<AsyncMap<String, String>> getMap() {
    if (map != null) {
      return Future.succeededFuture(map);
    }
    return vertx.sharedData().<String, String>getClusterWideMap(MAP_NAME)
      .onSuccess(m -> map = m);
  }

  // ---- 节点存活 ----

  /**
   * 查询节点是否存活。直接查 cluster manager 的 nodeInfo 目录——CM 已按 TTL 心跳维护，
   * 节点崩溃后条目过期即视为死亡。
   * @return 非集群模式、无 CM 或节点不在目录中时为 false
   */
  public Future<Boolean> isNodeAlive(String nodeId) {
    if (!vertx.isClustered() || clusterManager == null || nodeId == null) {
      return Future.succeededFuture(false);
    }
    Promise<NodeInfo> promise = Promise.promise();
    clusterManager.getNodeInfo(nodeId, promise);
    // 目录中没有该节点时 CM 以失败结束（Not a member of the cluster）
    return promise.future().map(Objects::nonNull).otherwise(false);
  }

  // ---- 路由条目 ----

  /**
   * 用户上线：写入路由条目（无 TTL，运行期零续期写）。
   * 节点存活由 CM 的 nodeInfo 目录管理（见 {@link #isNodeAlive}）；
   * 崩溃节点的残留路由由 PushRouter 发现死节点时惰性清理，或用户重连后覆盖。
   */
  public Future<Void> register(String userId) {
    if (!vertx.isClustered()) {
      return Future.succeededFuture();
    }
    return getMap().compose(m -> {
      Future<Void> f = m.put(userId, nodeId);
      f.onSuccess(v -> LOG.debug("Route registered: {} → {}", userId, nodeId));
      f.onFailure(e -> LOG.warn("Failed to register route: {} → {}", userId, nodeId, e));
      return f;
    });
  }

  /**
   * 用户离线：删除本节点登记的路由条目。
   * 仅当条目仍指向本节点时才移除——用户顶号迁移到其他节点后，
   * 旧节点迟到的断连清理不会误删新节点写入的路由。
   */
  public Future<Void> unregister(String userId) {
    return unregister(userId, nodeId);
  }

  /**
   * 条件删除路由：仅当条目当前指向 expectedNodeId 时移除。
   * 供 PushRouter 清理死节点残留路由使用（expectedNodeId 为解析出的死节点）。
   */
  public Future<Void> unregister(String userId, String expectedNodeId) {
    if (!vertx.isClustered() || userId == null) {
      return Future.succeededFuture();
    }
    return getMap()
      .compose(m -> m.removeIfPresent(userId, expectedNodeId).map(v -> (Void) null))
      .onSuccess(v -> LOG.debug("Route removed (if owned): {} → {}", userId, expectedNodeId))
      .onFailure(e -> LOG.warn("Failed to remove route: {} → {}", userId, expectedNodeId, e));
  }

  /**
   * 查询用户所在 Gateway 节点 ID。
   * @return nodeId，或 null（未找到）
   */
  public Future<String> resolve(String userId) {
    if (!vertx.isClustered() || userId == null) {
      return Future.succeededFuture(null);
    }
    return getMap().compose(m -> m.get(userId));
  }
}
