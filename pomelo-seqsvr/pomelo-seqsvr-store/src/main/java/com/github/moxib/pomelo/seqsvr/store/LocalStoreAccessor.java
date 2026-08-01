package com.github.moxib.pomelo.seqsvr.store;

import com.github.moxib.pomelo.seqsvr.proto.Router;
import com.github.moxib.pomelo.seqsvr.rpc.StoreAccessor;
import io.vertx.core.Future;

import java.io.IOException;

/**
 * 同进程内直接包装 {@link StoreManager} 的 StoreAccessor 实现。
 * <p>
 * 用于单元测试与开发模式（不经过 EventBus 网络往返），返回已完成 Future，行为与 StoreSvr 对齐。
 */
public class LocalStoreAccessor implements StoreAccessor {

  private final StoreManager storeManager;

  public LocalStoreAccessor(StoreManager storeManager) {
    this.storeManager = storeManager;
  }

  public StoreManager getStoreManager() {
    return storeManager;
  }

  @Override
  public Future<long[]> loadMaxSeqsData() {
    return Future.succeededFuture(storeManager.getMaxSeqsData());
  }

  @Override
  public Future<Long> saveMaxSeq(int id, long maxSeq) {
    return Future.succeededFuture(storeManager.setSectionMaxSeq(id, maxSeq));
  }

  @Override
  public Future<Router> loadRouteTable() {
    return Future.succeededFuture(storeManager.getCacheRouter());
  }

  @Override
  public Future<Void> saveRouteTable(Router router) {
    try {
      storeManager.saveCacheRouter(router);
      return Future.succeededFuture();
    } catch (IOException e) {
      return Future.failedFuture(e);
    }
  }
}
