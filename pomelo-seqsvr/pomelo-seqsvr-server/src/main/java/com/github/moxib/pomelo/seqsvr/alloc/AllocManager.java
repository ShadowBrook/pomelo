package com.github.moxib.pomelo.seqsvr.alloc;

import com.github.moxib.pomelo.seqsvr.proto.*;
import com.github.moxib.pomelo.seqsvr.store.StoreManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * 序列号分配管理器 — seqsvr 核心。
 * <p>
 * 关键算法：FetchNextSequence
 * 1. 内存中存储 sectionMaxSeqs（每 section 一个，共享）和 curSeqs（每 uid 一个）
 * 2. 分配时 curSeqs[id - idBegin]++，若超过 sectionMaxSeqs，则提升 max_seq 并持久化
 * 3. 重启时从 StoreManager 读出 sectionMaxSeqs，赋值给 curSeqs
 */
public class AllocManager {

  private static final Logger LOG = LoggerFactory.getLogger(AllocManager.class);

  private final StoreManager storeManager;
  private volatile AllocState state;

  // 路由表
  private volatile Router router;
  // 本节点在路由表中的条目
  private volatile RouterNode cacheMyNode;

  // section 级别共享的 max_seq，索引 = sectionIdx
  private long[] sectionMaxSeqs;
  // 每个用户独立的 cur_seq，索引 = uid - setId.idBegin
  private long[] curSeqs;

  // 当前 set 的范围
  private final RangeId setId;
  private final int maxIdSize;

  /**
   * 创建 AllocManager。
   */
  public AllocManager(StoreManager storeManager, RouterNode myNode, int maxIdSize) {
    this.storeManager = storeManager;
    this.cacheMyNode = myNode;
    this.maxIdSize = maxIdSize;
    this.setId = storeManager.getSetId();
    this.state = AllocState.NONE;

    // 从 StoreManager 加载缓存的路由表
    this.router = storeManager.getCacheRouter();
    if (this.router.getNodeList().isEmpty()) {
      this.router = new Router(0, Collections.singletonList(myNode));
    }
  }

  /**
   * 初始化 — 加载 max_seqs 数据
   */
  public void init() {
    state = AllocState.WAIT_ROUTE_TABLE;

    long[] maxSeqs = storeManager.getMaxSeqsData();
    onMaxSeqLoaded(maxSeqs);
  }

  private void onMaxSeqLoaded(long[] maxSeqs) {
    this.sectionMaxSeqs = maxSeqs;

    // curSeqs 数组大小 = 整个 set 内的 uid 数量
    // 每个 section 的 max_seq 拷贝到该 section 内所有 uid 的初始 cur_seq
    int sectionCount = this.sectionMaxSeqs.length;
    int totalUids = sectionCount * SeqSvrConstants.SECTION_SIZE;
    this.curSeqs = new long[totalUids];

    for (int i = 0; i < sectionCount; i++) {
      long sectionMax = sectionMaxSeqs[i];
      int baseIdx = i * SeqSvrConstants.SECTION_SIZE;
      for (int j = 0; j < SeqSvrConstants.SECTION_SIZE; j++) {
        curSeqs[baseIdx + j] = sectionMax;
      }
    }

    state = AllocState.INITED;
    LOG.info("AllocManager initialized: sections={}, totalUids={}, setId={}",
      sectionCount, totalUids, setId.getIdBegin());
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

    // 找到 uid 所属的 section range
    for (RangeId range : cacheMyNode.getSectionRanges()) {
      RangeId.SectionResult result = range.calcSectionID(id);
      if (!result.isFound()) continue;

      int sectionIdx = result.getSectionIdx();
      int uidOffset = id - range.getIdBegin();

      // 递增用户 cur_seq
      curSeqs[uidOffset]++;
      long curSeq = curSeqs[uidOffset];

      // 如果超出 section max，预分配下一批并持久化
      if (curSeq > sectionMaxSeqs[sectionIdx]) {
        saveMaxSeq(id, curSeq);
        sectionMaxSeqs[sectionIdx] += SeqSvrConstants.SEQ_STEP;
        LOG.debug("Section {} maxSeq bumped to {}", sectionIdx, sectionMaxSeqs[sectionIdx]);
      }

      // 构建响应
      Sequence seq = new Sequence(curSeq);

      // 如果客户端路由表过期，嵌入最新路由表
      if (router.isStale(clientVersion)) {
        seq.setRouter(router);
      }

      return seq;
    }

    throw new IllegalArgumentException(
      String.format("id %d not found in any section range of node %s", id, cacheMyNode.getNodeId()));
  }

  /**
   * 获取当前序列号（不递增）。
   *
   */
  public Sequence getCurrentSequence(int id, int clientVersion) {
    checkReady();

    for (RangeId range : cacheMyNode.getSectionRanges()) {
      RangeId.SectionResult result = range.calcSectionID(id);
      if (!result.isFound()) continue;

      int uidOffset = id - range.getIdBegin();
      Sequence seq = new Sequence(curSeqs[uidOffset]);

      if (router.isStale(clientVersion)) {
        seq.setRouter(router);
      }

      return seq;
    }

    throw new IllegalArgumentException(
      String.format("id %d not found in any section range of node %s", id, cacheMyNode.getNodeId()));
  }

  // ==================== 持久化 ====================

  private void saveMaxSeq(int id, long maxSeq) {
    storeManager.setSectionMaxSeq(id, maxSeq);
  }

  // ==================== 路由表管理 ====================

  /**
   * 更新路由表。
   * 对应 Go 的租约生效回调 OnLeaseValid / OnLeaseUpdated。
   */
  public void updateRouter(Router newRouter) {
    this.router = newRouter;
    // 从新路由表中找出本节点的条目
    for (RouterNode node : newRouter.getNodeList()) {
      if (node.getNodeId().equals(cacheMyNode.getNodeId())) {
        this.cacheMyNode = node;
        break;
      }
    }
    LOG.info("Router updated: version={}, nodes={}", newRouter.getVersion(), newRouter.getNodeList().size());
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

  /** 用于诊断 */
  public String getSectionInfo(int id) {
    for (RangeId range : cacheMyNode.getSectionRanges()) {
      RangeId.SectionResult result = range.calcSectionID(id);
      if (result.isFound()) {
        int idx = result.getSectionIdx();
        int offset = id - range.getIdBegin();
        return String.format("id=%d sectionIdx=%d sectionMax=%d curSeq=%d",
          id, idx, sectionMaxSeqs[idx], curSeqs[offset]);
      }
    }
    return "id=" + id + " not found";
  }
}
