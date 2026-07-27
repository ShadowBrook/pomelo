package com.github.moxib.pomelo.config;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.shareddata.AsyncMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * 用户 → Gateway 节点路由表（基于 Hazelcast 分布式 Map）。
 *
 * Gateway 节点在用户上线时写入，离线时删除。
 * Logic-Server 推送前查询目标节点，精确路由到具体 Gateway。
 * 查不到时 fallback 到 publish 广播。
 */
public final class SessionRouteTable {

  private static final Logger LOG = LoggerFactory.getLogger(SessionRouteTable.class);

  private static final String MAP_NAME = "__pomelo.session-routes";

  private final Vertx vertx;
  private final String nodeId;
  private volatile AsyncMap<String, String> map;

  public SessionRouteTable(Vertx vertx) {
    this.vertx = vertx;
    this.nodeId = UUID.randomUUID().toString().substring(0, 8);
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

  /** 用户上线：写入路由条目 */
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

  /** 用户离线：删除路由条目 */
  public Future<Void> unregister(String userId) {
    if (!vertx.isClustered() || userId == null) {
      return Future.succeededFuture();
    }
    return getMap().compose(m -> {
      Future<Void> f = m.remove(userId).mapEmpty();
      f.onSuccess(v -> LOG.debug("Route removed: {}", userId));
      f.onFailure(e -> LOG.warn("Failed to remove route: {}", userId, e));
      return f;
    });
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
