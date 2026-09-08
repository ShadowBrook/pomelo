package com.github.moxib.pomelo.config;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.shareddata.AsyncMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.UUID;

/**
 * 用户 → Gateway 节点路由表（基于分布式 Map）。
 *
 * Gateway 节点在用户上线时写入带 TTL 的路由条目，并由节点心跳定时续期；
 * 节点存活状态单独写入 live-gateways Map（同样带 TTL + 心跳续期）。
 * Logic-Server 推送前查询目标节点 + 校验节点存活，精确路由到具体 Gateway；
 * 节点已死或查不到时由 PushRouter fallback 到 publish 广播。
 */
public final class SessionRouteTable {

  private static final Logger LOG = LoggerFactory.getLogger(SessionRouteTable.class);

  private static final String MAP_NAME = "__pomelo.session-routes";
  private static final String LIVE_NODES_MAP = "__pomelo.live-gateways";

  private final Vertx vertx;
  private final String nodeId;
  private volatile AsyncMap<String, String> map;
  private volatile AsyncMap<String, String> liveNodesMap;

  public SessionRouteTable(Vertx vertx) {
    this.vertx = vertx;
    this.nodeId = UUID.randomUUID().toString();
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

  /** 获取 live-gateways Map，lazy init */
  private Future<AsyncMap<String, String>> getLiveNodesMap() {
    if (liveNodesMap != null) {
      return Future.succeededFuture(liveNodesMap);
    }
    return vertx.sharedData().<String, String>getClusterWideMap(LIVE_NODES_MAP)
      .onSuccess(m -> liveNodesMap = m);
  }

  // ---- 节点存活心跳 ----

  /**
   * Gateway 启动：注册本节点存活标记（带 TTL，由心跳续期）。
   * @param ttlMs 存活标记 TTL
   */
  public Future<Void> registerNode(long ttlMs) {
    if (!vertx.isClustered()) {
      return Future.succeededFuture();
    }
    return getLiveNodesMap().compose(m -> m.put(nodeId, "1", ttlMs)
      .onSuccess(v -> LOG.info("Gateway node heartbeat registered: {}", nodeId))
      .onFailure(e -> LOG.warn("Failed to register node heartbeat: {}", nodeId, e)));
  }

  /** Gateway 心跳：续期本节点存活标记 */
  public Future<Void> renewNode(long ttlMs) {
    if (!vertx.isClustered()) {
      return Future.succeededFuture();
    }
    return getLiveNodesMap().compose(m -> m.put(nodeId, "1", ttlMs));
  }

  /** Gateway 下线：移除本节点存活标记 */
  public Future<Void> unregisterNode() {
    if (!vertx.isClustered()) {
      return Future.succeededFuture();
    }
    return getLiveNodesMap()
      .compose(m -> m.remove(nodeId).map(v -> (Void) null))
      .onSuccess(v -> LOG.info("Gateway node heartbeat removed: {}", nodeId));
  }

  /** 查询节点是否存活 */
  public Future<Boolean> isNodeAlive(String nodeId) {
    if (!vertx.isClustered() || nodeId == null) {
      return Future.succeededFuture(false);
    }
    return getLiveNodesMap().compose(m -> m.get(nodeId))
      .map(Objects::nonNull);
  }

  /**
   * 用户上线：写入路由条目（无 TTL，运行期零续期写）。
   * 节点存活由 live-gateways 节点心跳管理（见 {@link #isNodeAlive}）；
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

  // ---- 路由条目 ----

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
