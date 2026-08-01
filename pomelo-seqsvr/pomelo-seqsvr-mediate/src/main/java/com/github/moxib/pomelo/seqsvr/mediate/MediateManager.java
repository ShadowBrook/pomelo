package com.github.moxib.pomelo.seqsvr.mediate;

import com.github.moxib.pomelo.seqsvr.proto.RangeId;
import com.github.moxib.pomelo.seqsvr.proto.Router;
import com.github.moxib.pomelo.seqsvr.proto.RouterNode;
import com.github.moxib.pomelo.seqsvr.proto.SeqSvrConstants;
import com.github.moxib.pomelo.seqsvr.rpc.StoreAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MediateSvr 仲裁核心 — 维护"号段 → AllocSvr"全映射路由表，驱动容灾与负载均衡。
 * <p>
 * 职责：
 * <ul>
 *   <li>AllocSvr 注册 / 优雅下线 / 心跳探测（lastSeen）</li>
 *   <li>路由表生成：将全量 section 按节点数量均匀分块（互为备机 + 负载均衡），version 自增</li>
 *   <li>心跳超时 → 失联节点被移除，其号段重新分摊到存活节点（故障迁移）</li>
 *   <li>路由表持久化到 StoreSvr（AllocSvr 租约读取，客户端响应旁路收敛）</li>
 * </ul>
 * <p>
 * 控制面低 QPS，方法同步即可；EventBus consumer + 定时器天然在单 context 上执行。
 */
public class MediateManager {

  private static final Logger LOG = LoggerFactory.getLogger(MediateManager.class);

  /** 心跳超时默认值：3 × 心跳周期 */
  public static final long HEARTBEAT_TIMEOUT_MS = 3000;

  private final StoreAccessor store;
  private final RangeId setId;
  private final int sectionCount;
  private final long heartbeatTimeoutMs;

  private final Map<String, NodeInfo> nodes = new HashMap<>();
  private volatile Router router;

  private static class NodeInfo {
    final RouterNode node;
    volatile long lastSeen;

    NodeInfo(RouterNode node, long lastSeen) {
      this.node = node;
      this.lastSeen = lastSeen;
    }
  }

  public MediateManager(StoreAccessor store, RangeId setId) {
    this(store, setId, HEARTBEAT_TIMEOUT_MS);
  }

  public MediateManager(StoreAccessor store, RangeId setId, long heartbeatTimeoutMs) {
    this.store = store;
    this.setId = setId;
    this.sectionCount = setId.calcSetSectionSize();
    this.heartbeatTimeoutMs = heartbeatTimeoutMs;
    this.router = new Router(0, Collections.emptyList());
  }

  // ==================== 注册 / 心跳 / 下线 ====================

  /**
   * 注册 AllocSvr（声明 nodeId / addr / 能力号段），返回生成后的当前路由表。
   */
  public synchronized Router register(RouterNode node) {
    if (node == null || node.getNodeId() == null || node.getNodeId().isBlank()) {
      throw new IllegalArgumentException("nodeId required for registerAllocSvr");
    }
    nodes.put(node.getNodeId(), new NodeInfo(node, System.currentTimeMillis()));
    LOG.info("AllocSvr registered: nodeId={}, ip={}, port={}, ranges={}",
      node.getNodeId(), node.getIp(), node.getPort(), node.getSectionRanges().size());
    return regenerateAndPersist();
  }

  /**
   * 优雅下线。
   */
  public synchronized Router unregister(String nodeId) {
    if (nodes.remove(nodeId) != null) {
      LOG.info("AllocSvr unregistered: nodeId={}", nodeId);
      return regenerateAndPersist();
    }
    return router;
  }

  /**
   * 心跳上报。未知节点忽略（未注册前不算存活）。
   */
  public synchronized boolean heartbeat(String nodeId, Map<String, Object> load) {
    NodeInfo info = nodes.get(nodeId);
    if (info == null) {
      return false;
    }
    info.lastSeen = System.currentTimeMillis();
    return true;
  }

  /**
   * 心跳超时检查（每 CHECK_INTERVAL 调用）：移除失联节点并重生成路由表。
   *
   * @return 是否有节点被移除（路由表变化）
   */
  public synchronized boolean checkTimeouts(long nowMs) {
    List<String> stale = new ArrayList<>();
    nodes.forEach((id, info) -> {
      if (nowMs - info.lastSeen > heartbeatTimeoutMs) {
        stale.add(id);
      }
    });
    if (stale.isEmpty()) {
      return false;
    }
    for (String id : stale) {
      LOG.warn("AllocSvr heartbeat timeout, removing: nodeId={}", id);
      nodes.remove(id);
    }
    regenerateAndPersist();
    return true;
  }

  // ==================== 路由表生成 ====================

  /**
   * 将全量 section 按存活节点数均匀分块，生成新路由表并持久化。
   * 所有节点互为备机；节点增减时号段自动重排（故障迁移 / 扩容缩容）。
   */
  private Router regenerateAndPersist() {
    Router newRouter = generateRouter();
    this.router = newRouter;
    store.saveRouteTable(newRouter).onFailure(err ->
      LOG.warn("saveRouteTable failed: version={}, cause={}", newRouter.getVersion(), err.getMessage()));
    LOG.info("Router regenerated: version={}, nodes={}", newRouter.getVersion(), newRouter.getNodeList().size());
    return newRouter;
  }

  private Router generateRouter() {
    if (nodes.isEmpty()) {
      return new Router(0, Collections.emptyList());
    }

    // 节点按 nodeId 排序，保证路由表确定性
    List<NodeInfo> alive = new ArrayList<>(nodes.values());
    alive.sort(Comparator.comparing(info -> info.node.getNodeId()));

    int n = alive.size();
    int chunk = sectionCount / n;
    int remainder = sectionCount % n;
    int cursor = 0;

    List<RouterNode> nodeList = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      int len = chunk + (i < remainder ? 1 : 0);
      int s0 = cursor;
      int s1 = s0 + len;
      // section [s0, s1) → id 范围 [setIdBegin + s0*SECTION_SIZE, ... )
      int idBegin = setId.getIdBegin() + s0 * SeqSvrConstants.SECTION_SIZE;
      int size = len * SeqSvrConstants.SECTION_SIZE;
      RouterNode node = alive.get(i).node;
      nodeList.add(new RouterNode(node.getNodeId(), node.getIp(), node.getPort(),
        Collections.singletonList(new RangeId(idBegin, size))));
      cursor = s1;
    }

    return new Router(router.getVersion() + 1, nodeList);
  }

  // ==================== Getters ====================

  public Router getRouter() { return router; }
  public int getSectionCount() { return sectionCount; }
  public int getNodeCount() { return nodes.size(); }
  public Map<String, Long> getLastSeen() {
    Map<String, Long> out = new HashMap<>();
    nodes.forEach((id, info) -> out.put(id, info.lastSeen));
    return out;
  }
}
