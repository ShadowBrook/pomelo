package com.github.moxib.pomelo.seqsvr.alloc;

import com.github.moxib.pomelo.seqsvr.proto.AllocState;
import com.github.moxib.pomelo.seqsvr.proto.RangeId;
import com.github.moxib.pomelo.seqsvr.proto.Router;
import com.github.moxib.pomelo.seqsvr.proto.RouterNode;
import com.github.moxib.pomelo.seqsvr.proto.SeqSvrConstants;
import com.github.moxib.pomelo.seqsvr.proto.Sequence;
import com.github.moxib.pomelo.seqsvr.rpc.StoreAccessor;
import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 序列号分配管理器 — seqsvr 核心。
 * <p>
 * 关键算法：FetchNextSequence
 * 1. 内存中存储 sectionMaxSeqs（每 section 一个，共享）和 curSeqs（每 id 一个，稀疏存储）
 * 2. 分配时 id 的 cur_seq 惰性自增：未分配过则从所属 section 的 max_seq 起步，若超过 sectionMaxSeqs，则提升 max_seq 并持久化
 * 3. 重启时从 StoreSvr 读出 sectionMaxSeqs，作为新 id 的起步值
 * <p>
 * 租约机制（对齐 Go Lease）：
 * <ul>
 *   <li>每 {@link #SYNC_LEASE_TIMEOUT_MS}（4s）从 StoreSvr 拉取路由表 → OnLeaseUpdated：立即卸载被收回号段，新增号段标记 pending</li>
 *   <li>每 {@link #CHECK_LEASE_TIMEOUT_MS}（1s）检查：pending 号段延迟 {@link #LEASE_TIMEOUT_MS}（5s）生效（保证旧 AllocSvr 已停止）；
 *       连续超过 leaseTimeout 无法读取 StoreSvr → 状态置 ERROR 停止发号（防止脏数据回退）</li>
 * </ul>
 * <p>
 * 持久化走 {@link StoreAccessor}（生产为跨进程 EventBus RPC）：
 * <ul>
 *   <li>init：异步加载路由表 → 找本节点条目 → 异步加载 maxSeqs → INITED</li>
 *   <li>saveMaxSeq：发号路径不阻塞等待，乐观提升内存 + 以 Store 对齐返回值回填（修复 Go 问题 3）</li>
 * </ul>
 * <p>
 * curSeqs 采用稀疏存储（Map&lt;Integer, Long&gt;），只物化实际分配过的 id，因此 maxIdSize 可以覆盖
 * 完整正数 int id 空间而不消耗巨量内存。
 * <p>
 * 线程模型：AllocManager 只被单一事件循环线程访问（EventBus consumer 天然串行 + 定时器同 context），
 * curSeqs / activeSections 无需加锁；EventBus 回复回调也在同一 context 执行。
 */
public class AllocManager {

  private static final Logger LOG = LoggerFactory.getLogger(AllocManager.class);

  // 租约常量（对齐 Go）
  public static final long CHECK_LEASE_TIMEOUT_MS = 1000;
  public static final long SYNC_LEASE_TIMEOUT_MS = 4000;
  public static final long LEASE_TIMEOUT_MS = 5000;

  private final StoreAccessor store;
  // 当前 set 的范围
  private final RangeId setId;
  private final int maxIdSize;
  private final String nodeId;
  private final long leaseTimeoutMs;
  // Mediate 模式：路由表为空时不自举单节点，等待 Mediate 分配号段（避免多节点启动时双写）
  private final boolean waitForRouter;

  private volatile AllocState state;
  // 路由表
  private volatile Router router;
  // 本节点在路由表中的条目
  private volatile RouterNode cacheMyNode;

  // section 级别共享的 max_seq，索引 = sectionIdx（set 维度）
  private long[] sectionMaxSeqs;
  // 启动时加载的 section max_seq 快照 —— 新 id 的起步值（等价于原稠密数组的初始值）
  private long[] baseSectionMaxSeqs;
  // 每个已分配 id 的独立 cur_seq，稀疏存储，键 = id
  private final Map<Integer, Long> curSeqs = new HashMap<>();

  // 当前实际服务的 section（不含 pending 中的新增号段）
  private final Set<Integer> activeSections = new HashSet<>();
  // 新增号段（租约延迟生效）：sectionIdx → 标记 pending 的时刻
  private final Map<Integer, Long> pendingSections = new HashMap<>();
  // 最近一次成功读取 Store 路由表的时刻（租约心跳）
  private volatile long lastLeaseSuccess;

  /**
   * 创建 AllocManager。
   *
   * @param store      存储层访问接口（生产为 EventBusStoreClient）
   * @param setId      本 Set 的 id 范围
   * @param myNode     本节点条目（nodeId / ip / port / 声明号段）
   * @param maxIdSize  服务的最大 id 空间
   */
  public AllocManager(StoreAccessor store, RangeId setId, RouterNode myNode, int maxIdSize) {
    this(store, setId, myNode, maxIdSize, LEASE_TIMEOUT_MS, false);
  }

  public AllocManager(StoreAccessor store, RangeId setId, RouterNode myNode, int maxIdSize, long leaseTimeoutMs) {
    this(store, setId, myNode, maxIdSize, leaseTimeoutMs, false);
  }

  public AllocManager(StoreAccessor store, RangeId setId, RouterNode myNode, int maxIdSize, long leaseTimeoutMs,
                      boolean waitForRouter) {
    this.store = store;
    this.setId = setId;
    this.cacheMyNode = myNode;
    this.nodeId = myNode.getNodeId();
    this.maxIdSize = maxIdSize;
    this.leaseTimeoutMs = leaseTimeoutMs;
    this.waitForRouter = waitForRouter;
    this.state = AllocState.NONE;
    this.router = new Router(0, Collections.emptyList());
  }

  /**
   * 初始化 — 加载路由表与 max_seqs 数据。
   * <p>
   * 路由表为空时：
   * <ul>
   *   <li>开发模式（waitForRouter=false）：自举为单节点（覆盖全部分区）</li>
   *   <li>Mediate 模式（waitForRouter=true）：保持 WAIT_ROUTE_TABLE，等待注册后 Mediate 分配号段
   *       （applyRouter 首次获得号段时加载 max_seqs 并进入 INITED）</li>
   * </ul>
   */
  public Future<Void> init() {
    state = AllocState.WAIT_ROUTE_TABLE;

    return store.loadRouteTable()
      .compose(loaded -> {
        if (loaded.getNodeList().isEmpty()) {
          if (waitForRouter) {
            // Mediate 模式：等待路由表（register 后 applyRouter 触发 maxSeq 加载）
            LOG.info("no router yet, waiting for Mediate to assign sections: nodeId={}", nodeId);
            return Future.<Void>succeededFuture();
          }
          // 无路由表：开发模式单节点自举
          this.router = new Router(0, Collections.singletonList(cacheMyNode));
        } else {
          this.router = loaded;
          findMyNode(loaded);
        }
        state = AllocState.WAIT_LOAD;
        return store.loadMaxSeqsData().compose(maxSeqs -> {
          onMaxSeqLoaded(maxSeqs);
          return Future.<Void>succeededFuture();
        });
      })
      .onFailure(err -> {
        if (!waitForRouter) {
          state = AllocState.ERROR;
        }
        LOG.error("AllocManager init failed: nodeId={}", nodeId, err);
      });
  }

  private void onMaxSeqLoaded(long[] maxSeqs) {
    this.sectionMaxSeqs = maxSeqs;
    // 快照启动时的 section max，作为未分配 id 惰性起步值
    this.baseSectionMaxSeqs = maxSeqs.clone();
    curSeqs.clear();
    // 初始化为本节点当前拥有的全部 section
    activeSections.clear();
    activeSections.addAll(coveredSections(cacheMyNode));
    pendingSections.clear();
    lastLeaseSuccess = System.currentTimeMillis();

    state = AllocState.INITED;
    LOG.info("AllocManager initialized: nodeId={}, sections={}, active={}, setId={}",
      nodeId, sectionMaxSeqs.length, activeSections.size(), setId.getIdBegin());
  }

  // ==================== 租约 ====================

  /**
   * 租约同步（每 SYNC_LEASE_TIMEOUT_MS 由 Verticle 定时调用）。
   * 从 StoreSvr 拉取路由表并应用；成功即刷新租约心跳。
   */
  public Future<Void> syncLease() {
    return store.loadRouteTable()
      .onSuccess(this::onLeaseRouteLoaded)
      .onFailure(err -> LOG.warn("lease sync failed: nodeId={}, cause={}", nodeId, err.getMessage()))
      .mapEmpty();
  }

  private void onLeaseRouteLoaded(Router newRouter) {
    lastLeaseSuccess = System.currentTimeMillis();
    if (newRouter.getNodeList().isEmpty()) {
      // Store 尚无路由表（未部署 Mediate / 开发模式）：保持现状
      return;
    }
    if (newRouter.getVersion() == router.getVersion()) {
      // 路由表无变化
      return;
    }
    applyRouter(newRouter);
  }

  /**
   * 租约检查（每 CHECK_LEASE_TIMEOUT_MS 由 Verticle 定时调用）。
   * 1. 激活已到生效期的 pending 号段；
   * 2. 连续超过 leaseTimeout 无法读取 StoreSvr → ERROR 停止发号；恢复后重载 max_seqs 回到 INITED。
   */
  public void checkLease(long nowMs) {
    activatePendingSections(nowMs);

    if (nowMs - lastLeaseSuccess > leaseTimeoutMs) {
      if (state == AllocState.INITED) {
        LOG.error("lease expired: no successful store read for {}ms, stop serving", leaseTimeoutMs);
      }
      state = AllocState.ERROR;
    } else if (state == AllocState.ERROR) {
      // 租约恢复：重载持久化 max_seq（保证此前可能未落盘的已发序号被覆盖）后恢复服务
      LOG.info("lease recovered: reloading max_seqs and resuming");
      store.loadMaxSeqsData().onSuccess(this::onMaxSeqLoaded);
    }
  }

  /**
   * 将到期 pending 号段激活为服务中，并从 Store 刷新其 max_seq（防止回退）。
   */
  private void activatePendingSections(long nowMs) {
    if (pendingSections.isEmpty()) {
      return;
    }
    Set<Integer> toActivate = new HashSet<>();
    pendingSections.forEach((s, since) -> {
      if (nowMs - since >= leaseTimeoutMs) {
        toActivate.add(s);
      }
    });
    if (toActivate.isEmpty()) {
      return;
    }
    pendingSections.keySet().removeAll(toActivate);
    activeSections.addAll(toActivate);
    refreshSectionMaxSeqs(toActivate);
    LOG.info("sections activated (pending lease elapsed): {}", toActivate);
  }

  /**
   * 应用新路由表：卸载被收回号段（立即），新增号段标记 pending（延迟生效）。
   */
  private void applyRouter(Router newRouter) {
    RouterNode newMyNode = null;
    for (RouterNode n : newRouter.getNodeList()) {
      if (n.getNodeId().equals(nodeId)) {
        newMyNode = n;
        break;
      }
    }
    if (newMyNode == null) {
      // 本节点被整体移除：停止服务所有号段，等待重新分配
      LOG.warn("node {} removed from router v{}", nodeId, newRouter.getVersion());
      activeSections.clear();
      pendingSections.clear();
      this.router = newRouter;
      state = AllocState.WAIT_ROUTE_TABLE;
      return;
    }

    Set<Integer> newSections = coveredSections(newMyNode);
    Set<Integer> prevSections = new HashSet<>(activeSections);

    // 卸载被收回的号段（立即生效，防止双写）
    Set<Integer> removed = new HashSet<>(prevSections);
    removed.removeAll(newSections);
    unloadSections(removed);

    this.cacheMyNode = newMyNode;
    this.router = newRouter;

    // 首次获得路由（等待 Mediate 分配后）：直接加载 max_seq 并激活，无需 pending（初始分配无旧节点需等待）
    if (state == AllocState.WAIT_ROUTE_TABLE || sectionMaxSeqs == null) {
      store.loadMaxSeqsData().onSuccess(this::onMaxSeqLoaded);
      return;
    }

    // 正常租约更新：新增号段标记 pending（延迟 leaseTimeout 生效，保证旧 AllocSvr 已停止服务）
    Set<Integer> added = new HashSet<>(newSections);
    added.removeAll(prevSections);
    long now = System.currentTimeMillis();
    for (int s : added) {
      pendingSections.putIfAbsent(s, now);
    }
    if (!added.isEmpty()) {
      LOG.info("sections marked pending (will activate in {}ms): {}", leaseTimeoutMs, added);
    }
  }

  private void unloadSections(Set<Integer> removed) {
    if (removed.isEmpty()) {
      return;
    }
    activeSections.removeAll(removed);
    pendingSections.keySet().removeAll(removed);
    // 从 curSeqs 剔除被收回号段的 id
    curSeqs.entrySet().removeIf(e -> removed.contains(sectionIndex(e.getKey())));
    LOG.info("sections unloaded (retracted): {}", removed);
  }

  private void refreshSectionMaxSeqs(Set<Integer> sections) {
    store.loadMaxSeqsData().onSuccess(maxSeqs -> {
      for (int s : sections) {
        if (s < maxSeqs.length && s < sectionMaxSeqs.length) {
          if (maxSeqs[s] > sectionMaxSeqs[s]) {
            sectionMaxSeqs[s] = maxSeqs[s];
          }
          if (maxSeqs[s] > baseSectionMaxSeqs[s]) {
            baseSectionMaxSeqs[s] = maxSeqs[s];
          }
        }
      }
    });
  }

  // ==================== 核心分配算法 ====================

  /**
   * 获取下一个序列号。
   *
   * @param id            用户 uid（全局空间内的 id）
   * @param clientVersion 客户端缓存的路由表版本号
   * @return Sequence，如果客户端路由过期则携带最新 Router
   */
  public Sequence fetchNextSequence(int id, int clientVersion) {
    checkReady();

    if (id < setId.getIdBegin() || id >= (long) setId.getIdBegin() + setId.getSize()) {
      throw new IllegalArgumentException(
        String.format("id %d out of set [%d, %d)", id, setId.getIdBegin(), (long) setId.getIdBegin() + setId.getSize()));
    }
    int sectionIdx = sectionIndex(id);
    if (!activeSections.contains(sectionIdx)) {
      throw new IllegalArgumentException(
        String.format("id %d not in an active section (section %d) of node %s", id, sectionIdx, nodeId));
    }

    // 递增用户 cur_seq：已分配则取自身值，未分配则从该 section 启动时的 max_seq 惰性起步
    long curSeq = curSeqs.getOrDefault(id, baseSectionMaxSeqs[sectionIdx]) + 1;
    curSeqs.put(id, curSeq);

    // 如果超出 section max，预分配下一批并持久化
    if (curSeq > sectionMaxSeqs[sectionIdx]) {
      bumpSection(id, sectionIdx, curSeq);
    }

    // 构建响应
    Sequence seq = new Sequence(curSeq);

    // 如果客户端路由表过期，嵌入最新路由表
    if (router.isStale(clientVersion)) {
      seq.setRouter(router);
    }

    return seq;
  }

  /**
   * 获取当前序列号（不递增）。
   */
  public Sequence getCurrentSequence(int id, int clientVersion) {
    checkReady();

    if (id < setId.getIdBegin() || id >= (long) setId.getIdBegin() + setId.getSize()) {
      throw new IllegalArgumentException(
        String.format("id %d out of set [%d, %d)", id, setId.getIdBegin(), (long) setId.getIdBegin() + setId.getSize()));
    }
    int sectionIdx = sectionIndex(id);
    if (!activeSections.contains(sectionIdx)) {
      throw new IllegalArgumentException(
        String.format("id %d not in an active section (section %d) of node %s", id, sectionIdx, nodeId));
    }

    long cur = curSeqs.getOrDefault(id, baseSectionMaxSeqs[sectionIdx]);
    Sequence seq = new Sequence(cur);

    if (router.isStale(clientVersion)) {
      seq.setRouter(router);
    }

    return seq;
  }

  // ==================== 持久化 ====================

  /**
   * 提升 section max_seq 并异步持久化。
   * <p>
   * 发号路径不阻塞等待网络往返：先用向上取整到 SEQ_STEP 的对齐值乐观提升内存（与 Store 计算一致），
   * 再异步 saveMaxSeq，收到 Store 对齐返回值后以 max 合并回填（修复 Go 问题 3 —— SaveMaxSeq 返回值未回填）。
   */
  private void bumpSection(int id, int sectionIdx, long curSeq) {
    long optimistic = alignUp(curSeq);
    if (optimistic > sectionMaxSeqs[sectionIdx]) {
      sectionMaxSeqs[sectionIdx] = optimistic;
    }

    store.saveMaxSeq(id, curSeq).onComplete(ar -> {
      if (ar.succeeded()) {
        long aligned = ar.result();
        if (aligned > sectionMaxSeqs[sectionIdx]) {
          sectionMaxSeqs[sectionIdx] = aligned;
        }
      } else {
        // 持久化失败：租约机制会因 Store 不可用而停止服务，防止回退
        LOG.warn("saveMaxSeq failed: nodeId={}, id={}, curSeq={}, cause={}",
          nodeId, id, curSeq, ar.cause().getMessage());
      }
    });
  }

  private static long alignUp(long v) {
    return ((v / SeqSvrConstants.SEQ_STEP) + 1) * SeqSvrConstants.SEQ_STEP;
  }

  // ==================== 路由表管理 ====================

  /**
   * 手动注入路由表（测试 / 运维）。等价于租约回调 OnLeaseUpdated。
   */
  public void updateRouter(Router newRouter) {
    if (newRouter.getNodeList().isEmpty()) {
      return;
    }
    applyRouter(newRouter);
    LOG.info("Router updated (manual): version={}, nodes={}",
      newRouter.getVersion(), newRouter.getNodeList().size());
  }

  private void findMyNode(Router newRouter) {
    for (RouterNode node : newRouter.getNodeList()) {
      if (node.getNodeId().equals(nodeId)) {
        this.cacheMyNode = node;
        return;
      }
    }
    // 本节点不在新路由表中（被收回 / 迁移）：保留现有条目，等待租约重新分配
    LOG.warn("node {} not found in router v{}, nodes={}",
      nodeId, newRouter.getVersion(), newRouter.getNodeList().size());
  }

  // ==================== 工具 ====================

  private int sectionIndex(int id) {
    return (id - setId.getIdBegin()) / SeqSvrConstants.SECTION_SIZE;
  }

  /** 该节点 sectionRanges 覆盖的全部 section 索引（set 维度） */
  private Set<Integer> coveredSections(RouterNode node) {
    Set<Integer> out = new HashSet<>();
    for (RangeId r : node.getSectionRanges()) {
      long first = ((long) r.getIdBegin() - setId.getIdBegin()) / SeqSvrConstants.SECTION_SIZE;
      long last = ((long) r.getIdBegin() + r.getSize() - 1 - setId.getIdBegin()) / SeqSvrConstants.SECTION_SIZE;
      for (long s = first; s <= last; s++) {
        out.add((int) s);
      }
    }
    return out;
  }

  // ==================== 状态检查 ====================

  private void checkReady() {
    if (cacheMyNode == null) {
      throw new IllegalStateException("cacheMyNode is null — allocator not registered");
    }
    if (state != AllocState.INITED) {
      throw new IllegalStateException("AllocManager not inited, current state: " + state);
    }
  }

  // ==================== Getters ====================

  public AllocState getState() { return state; }
  public Router getRouter() { return router; }
  public RouterNode getCacheMyNode() { return cacheMyNode; }
  public int getMaxIdSize() { return maxIdSize; }
  public String getNodeId() { return nodeId; }

  /** 当前实际服务的 section 集合（不含 pending） */
  public Set<Integer> getActiveSections() { return Collections.unmodifiableSet(activeSections); }

  /**
   * 本节点是否在服务该 id 的号段（用于 AllocVerticle 区分"路由过期"与"非法 id"）。
   */
  public boolean isServing(int id) {
    if (id < setId.getIdBegin() || id >= (long) setId.getIdBegin() + setId.getSize()) {
      return false;
    }
    return activeSections.contains(sectionIndex(id));
  }

  /** pending 中的 section → 标记时刻 */
  public Map<Integer, Long> getPendingSections() { return Collections.unmodifiableMap(pendingSections); }

  /** 测试注入：把租约心跳回拨，模拟长时间无法读取 StoreSvr */
  void backdateLeaseForTest(long nowMs) {
    this.lastLeaseSuccess = nowMs;
  }

  /** 诊断：某 section 当前内存中的 max_seq */
  public long getSectionMaxSeq(int sectionIdx) {
    return sectionMaxSeqs == null ? 0 : sectionMaxSeqs[sectionIdx];
  }

  /** 诊断：某 section 的惰性起步 max_seq */
  public long getBaseSectionMaxSeq(int sectionIdx) {
    return baseSectionMaxSeqs == null ? 0 : baseSectionMaxSeqs[sectionIdx];
  }

  /** 用于诊断 */
  public String getSectionInfo(int id) {
    int sectionIdx = sectionIndex(id);
    long cur = curSeqs.getOrDefault(id, baseSectionMaxSeqs == null ? 0 : baseSectionMaxSeqs[sectionIdx]);
    long max = sectionMaxSeqs == null ? 0 : sectionMaxSeqs[sectionIdx];
    return String.format("id=%d sectionIdx=%d sectionMax=%d curSeq=%d active=%s",
      id, sectionIdx, max, cur, activeSections.contains(sectionIdx));
  }
}
