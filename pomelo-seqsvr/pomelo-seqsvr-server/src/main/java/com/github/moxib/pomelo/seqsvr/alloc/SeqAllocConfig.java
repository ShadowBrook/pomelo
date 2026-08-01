package com.github.moxib.pomelo.seqsvr.alloc;

import com.github.moxib.pomelo.config.ConfigHolder;
import com.github.moxib.pomelo.seqsvr.proto.SeqSvrConstants;
import com.github.moxib.pomelo.seqsvr.rpc.SeqSvrAddresses;

/**
 * AllocSvr 配置（可注入，便于单进程部署多节点集成测试）。
 * 默认从 ConfigHolder / 系统属性读取。
 * <p>
 * storeReplicas 非空时走 NRW 多副本 StoreClient（Phase 5），否则单副本 EventBus。
 */
public record SeqAllocConfig(
  int maxIdSize,
  int setIdBegin,
  int setIdSize,
  String nodeId,
  String ip,
  int port,
  String storePrefix,
  boolean mediateEnabled,
  String mediatePrefix,
  long heartbeatMs,
  long syncLeaseMs,
  long checkLeaseMs,
  long leaseMs,
  String storeReplicas,
  int storeW,
  int storeR
) {

  /** 兼容旧签名：默认单副本 Store */
  public SeqAllocConfig(int maxIdSize, int setIdBegin, int setIdSize, String nodeId, String ip, int port,
                        String storePrefix, boolean mediateEnabled, String mediatePrefix,
                        long heartbeatMs, long syncLeaseMs, long checkLeaseMs, long leaseMs) {
    this(maxIdSize, setIdBegin, setIdSize, nodeId, ip, port, storePrefix, mediateEnabled, mediatePrefix,
      heartbeatMs, syncLeaseMs, checkLeaseMs, leaseMs, "", 2, 2);
  }

  public static SeqAllocConfig fromConfig() {
    int maxIdSize = ConfigHolder.getInt("seqsvr.maxIdSize", SeqSvrConstants.PRODUCTION_MAX_ID_SIZE);
    int setIdBegin = ConfigHolder.getInt("seqsvr.setIdBegin", 0);
    int setIdSize = ConfigHolder.getInt("seqsvr.setIdSize", maxIdSize);
    return new SeqAllocConfig(
      maxIdSize,
      setIdBegin,
      setIdSize,
      ConfigHolder.getString("seqsvr.nodeId", "node-1"),
      ConfigHolder.getString("seqsvr.ip", "127.0.0.1"),
      ConfigHolder.getInt("seqsvr.port", 10100),
      ConfigHolder.getString("seqsvr.store.address", SeqSvrAddresses.STORE_PREFIX),
      ConfigHolder.getBoolean("seqsvr.mediate.enabled", false),
      ConfigHolder.getString("seqsvr.mediate.address", SeqSvrAddresses.MEDIATE_PREFIX),
      ConfigHolder.getLong("seqsvr.mediate.heartbeatIntervalMs", 1000),
      ConfigHolder.getLong("seqsvr.syncLeaseTimeoutMs", AllocManager.SYNC_LEASE_TIMEOUT_MS),
      ConfigHolder.getLong("seqsvr.checkLeaseTimeoutMs", AllocManager.CHECK_LEASE_TIMEOUT_MS),
      ConfigHolder.getLong("seqsvr.leaseTimeoutMs", AllocManager.LEASE_TIMEOUT_MS),
      ConfigHolder.getString("seqsvr.store.replicas", ""),
      ConfigHolder.getInt("seqsvr.store.w", 2),
      ConfigHolder.getInt("seqsvr.store.r", 2)
    );
  }
}
