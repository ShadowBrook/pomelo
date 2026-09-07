package com.github.moxib.pomelo.seqsvr.mediate;

import com.github.moxib.pomelo.seqsvr.proto.RangeId;
import com.github.moxib.pomelo.seqsvr.proto.Router;
import com.github.moxib.pomelo.seqsvr.proto.RouterNode;
import com.github.moxib.pomelo.seqsvr.proto.SeqSvrConstants;
import com.github.moxib.pomelo.seqsvr.rpc.StoreAccessor;
import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
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

  /**
   * 启动时从 Store 载入上一版路由表，作为增量迁移的基准，避免重启后从零重排。
   * 载入失败（Store 未就绪）时保持空路由表，由节点重新注册触发重建。
   */
  public Future<Void> init() {
    return store.loadRouteTable()
      .onSuccess(loaded -> {
        if (loaded != null && !loaded.getNodeList().isEmpty()) {
          this.router = loaded;
          LOG.info("MediateManager loaded persisted router: version={}, nodes={}",
            loaded.getVersion(), loaded.getNodeList().size());
        }
      })
      .recover(err -> {
        LOG.warn("load persisted router failed, starting with empty assignment: {}", err.getMessage());
        return Future.succeededFuture();
      })
      .mapEmpty();
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
      // 版本必须单调递增：全部节点失联被移除时归零会让持有旧版本的客户端
      // 永久拒绝后续新路由表（2026-09-07 线上事故根因之一）
      return new Router(router.getVersion() + 1, Collections.emptyList());
    }

    // 存活节点按 nodeId 排序，保证路由表确定性
    List<String> aliveIds = new ArrayList<>(nodes.keySet());
    aliveIds.sort(String::compareTo);

    int n = aliveIds.size();
    Map<String, List<Integer>> owned = currentOwnership(aliveIds);
    List<Integer> free = freeSections(owned);

    int base = sectionCount / n;
    int remainder = sectionCount % n;
    Map<String, Integer> target = new HashMap<>();
    for (int i = 0; i < n; i++) {
      target.put(aliveIds.get(i), base + (i < remainder ? 1 : 0));
    }

    balance(owned, free, target, aliveIds);

    List<RouterNode> nodeList = new ArrayList<>(n);
    for (String id : aliveIds) {
      RouterNode node = nodes.get(id).node;
      nodeList.add(new RouterNode(node.getNodeId(), node.getIp(), node.getPort(), toRanges(owned.get(id))));
    }
    return new Router(router.getVersion() + 1, nodeList);
  }

  /**
   * 当前存活节点各自持有的 section 索引（升序），来源 = 上一版路由表。
   * 已下线节点持有的号段不在此列，会进入 free。
   */
  private Map<String, List<Integer>> currentOwnership(List<String> aliveIds) {
    Map<String, List<Integer>> owned = new HashMap<>();
    for (String id : aliveIds) {
      owned.put(id, new ArrayList<>());
    }
    for (RouterNode rn : router.getNodeList()) {
      if (!aliveIds.contains(rn.getNodeId())) {
        continue;
      }
      List<Integer> secs = owned.get(rn.getNodeId());
      for (RangeId range : rn.getSectionRanges()) {
        int first = (range.getIdBegin() - setId.getIdBegin()) / SeqSvrConstants.SECTION_SIZE;
        int count = range.calcSetSectionSize();
        for (int s = first; s < first + count; s++) {
          secs.add(s);
        }
      }
      Collections.sort(secs);
    }
    return owned;
  }

  /**
   * 未被任何存活节点持有的 section 索引（升序）——下线节点遗留或从未分配。
   */
  private List<Integer> freeSections(Map<String, List<Integer>> owned) {
    boolean[] used = new boolean[sectionCount];
    for (List<Integer> secs : owned.values()) {
      for (int s : secs) {
        used[s] = true;
      }
    }
    List<Integer> free = new ArrayList<>();
    for (int s = 0; s < sectionCount; s++) {
      if (!used[s]) {
        free.add(s);
      }
    }
    return free;
  }

  /**
   * 贪心迁移：超载节点从尾部割出 section 给欠载节点；欠载仍不足时从 free 补齐。
   * 每次只移动最少的 section，避免存量节点之间无谓重排。
   */
  private void balance(Map<String, List<Integer>> owned, List<Integer> free,
                       Map<String, Integer> target, List<String> aliveIds) {
    while (true) {
      String donor = null;
      String receiver = null;
      int maxSurplus = 0;
      int maxDeficit = 0;
      for (String id : aliveIds) {
        int diff = owned.get(id).size() - target.get(id);
        if (diff > maxSurplus) {
          donor = id;
          maxSurplus = diff;
        }
        if (-diff > maxDeficit) {
          receiver = id;
          maxDeficit = -diff;
        }
      }
      if (maxSurplus == 0 && maxDeficit == 0) {
        return;
      }
      if (donor != null && receiver != null) {
        moveTail(owned.get(donor), owned.get(receiver), Math.min(maxSurplus, maxDeficit));
      } else if (receiver != null) {
        moveTail(free, owned.get(receiver), Math.min(maxDeficit, free.size()));
      } else {
        // 仅有超载节点（sum(target)==sectionCount 时不会出现），防御性结束
        return;
      }
    }
  }

  /** 从 from 尾部移出 k 个元素追加到 to。 */
  private void moveTail(List<Integer> from, List<Integer> to, int k) {
    for (int i = 0; i < k; i++) {
      to.add(from.remove(from.size() - 1));
    }
  }

  /** 把升序 section 索引列表合并成连续的 RangeId。 */
  private List<RangeId> toRanges(List<Integer> secs) {
    if (secs.isEmpty()) {
      return Collections.emptyList();
    }
    Collections.sort(secs);
    long setIdBeginL = setId.getIdBegin();
    long setIdEnd = setIdBeginL + setId.getSize();
    List<RangeId> ranges = new ArrayList<>();
    int start = secs.get(0);
    int prev = start;
    for (int i = 1; i < secs.size(); i++) {
      int cur = secs.get(i);
      if (cur != prev + 1) {
        ranges.add(buildRange(setIdBeginL, setIdEnd, start, prev));
        start = cur;
      }
      prev = cur;
    }
    ranges.add(buildRange(setIdBeginL, setIdEnd, start, prev));
    return ranges;
  }

  private RangeId buildRange(long setIdBeginL, long setIdEnd, int startSection, int endSection) {
    long idBegin = setIdBeginL + (long) startSection * SeqSvrConstants.SECTION_SIZE;
    long idEnd = Math.min(setIdEnd, setIdBeginL + (long) (endSection + 1) * SeqSvrConstants.SECTION_SIZE);
    return new RangeId((int) idBegin, (int) (idEnd - idBegin));
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
