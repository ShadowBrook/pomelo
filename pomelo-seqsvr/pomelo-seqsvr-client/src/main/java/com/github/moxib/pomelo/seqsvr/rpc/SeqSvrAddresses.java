package com.github.moxib.pomelo.seqsvr.rpc;

/**
 * seqsvr 各服务 EventBus 地址常量。
 * <p>
 * 开发模式可用单机非集群 EventBus；生产跨进程时用 Redis 集群 EventBus（ClusterHelper）。
 */
public final class SeqSvrAddresses {

  private SeqSvrAddresses() {}

  // ==================== StoreSvr ====================

  public static final String STORE_PREFIX = "seqsvr.store";

  public static final String STORE_LOAD_MAX_SEQS = STORE_PREFIX + ".loadMaxSeqsData";
  public static final String STORE_SAVE_MAX_SEQ = STORE_PREFIX + ".saveMaxSeq";
  public static final String STORE_LOAD_ROUTE_TABLE = STORE_PREFIX + ".loadRouteTable";
  public static final String STORE_SAVE_ROUTE_TABLE = STORE_PREFIX + ".saveRouteTable";

  /** StoreSvr 副本专属地址（Phase 5 NRW 使用）：seqsvr.store.<replicaId>.saveMaxSeq */
  public static String storeReplicaAddress(String replicaId, String op) {
    return STORE_PREFIX + "." + replicaId + "." + op;
  }

  // ==================== AllocSvr ====================

  public static final String ALLOC_PREFIX = "seqsvr.alloc";

  /** 兼容地址：单节点开发模式 / 客户端无路由表时的兜底（多节点下 round-robin，不能做号段路由） */
  public static final String ALLOC_FETCH_NEXT = ALLOC_PREFIX + ".fetchNext";
  public static final String ALLOC_GET_CURRENT = ALLOC_PREFIX + ".getCurrent";

  /** 按 nodeId 路由的地址：客户端凭路由表把请求发到拥有该号段的节点 */
  public static String allocNodeFetchNext(String nodeId) {
    return ALLOC_PREFIX + "." + nodeId + ".fetchNext";
  }

  public static String allocNodeGetCurrent(String nodeId) {
    return ALLOC_PREFIX + "." + nodeId + ".getCurrent";
  }

  // ==================== MediateSvr ====================

  public static final String MEDIATE_PREFIX = "seqsvr.mediate";

  public static final String MEDIATE_REGISTER = MEDIATE_PREFIX + ".registerAllocSvr";
  public static final String MEDIATE_UNREGISTER = MEDIATE_PREFIX + ".unRegisterAllocSvr";
  public static final String MEDIATE_HEARTBEAT = MEDIATE_PREFIX + ".heartbeat";
  public static final String MEDIATE_GET_ROUTER = MEDIATE_PREFIX + ".getRouter";
}
