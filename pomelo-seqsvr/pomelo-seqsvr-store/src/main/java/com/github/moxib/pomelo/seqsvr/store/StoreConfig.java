package com.github.moxib.pomelo.seqsvr.store;

import com.github.moxib.pomelo.config.ConfigHolder;
import com.github.moxib.pomelo.seqsvr.proto.SeqSvrConstants;

/**
 * StoreSvr 配置（可注入，便于单进程部署多副本集成测试）。
 * 默认从 ConfigHolder / 系统属性读取。
 */
public record StoreConfig(
  String dataDir,
  int setIdBegin,
  int setIdSize,
  String replicaId
) {

  public static StoreConfig fromConfig() {
    int setIdSize = ConfigHolder.getInt("seqsvr.setIdSize", SeqSvrConstants.PRODUCTION_MAX_ID_SIZE);
    return new StoreConfig(
      ConfigHolder.getString("seqsvr.dataDir", "data/seqsvr"),
      ConfigHolder.getInt("seqsvr.setIdBegin", 0),
      setIdSize,
      ConfigHolder.getString("seqsvr.store.replicaId", "r1"));
  }
}
