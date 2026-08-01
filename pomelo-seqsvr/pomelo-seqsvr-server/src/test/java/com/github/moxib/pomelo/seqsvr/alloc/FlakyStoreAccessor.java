package com.github.moxib.pomelo.seqsvr.alloc;

import com.github.moxib.pomelo.seqsvr.proto.Router;
import com.github.moxib.pomelo.seqsvr.rpc.StoreAccessor;
import io.vertx.core.Future;

/**
 * 可注入故障的 StoreAccessor 装饰器 — 模拟 StoreSvr 不可用。
 */
class FlakyStoreAccessor implements StoreAccessor {

  private final StoreAccessor delegate;
  private volatile boolean failReads;
  private volatile boolean failWrites;

  FlakyStoreAccessor(StoreAccessor delegate) {
    this.delegate = delegate;
  }

  void setFailReads(boolean failReads) {
    this.failReads = failReads;
  }

  void setFailWrites(boolean failWrites) {
    this.failWrites = failWrites;
  }

  private static <T> Future<T> fail(String what) {
    return Future.failedFuture(new IllegalStateException("store " + what + " unavailable"));
  }

  @Override
  public Future<long[]> loadMaxSeqsData() {
    if (failReads) {
      return fail("read");
    }
    return delegate.loadMaxSeqsData();
  }

  @Override
  public Future<Long> saveMaxSeq(int id, long maxSeq) {
    if (failWrites) {
      return fail("write");
    }
    return delegate.saveMaxSeq(id, maxSeq);
  }

  @Override
  public Future<Router> loadRouteTable() {
    if (failReads) {
      return fail("read");
    }
    return delegate.loadRouteTable();
  }

  @Override
  public Future<Void> saveRouteTable(Router router) {
    if (failWrites) {
      return fail("write");
    }
    return delegate.saveRouteTable(router);
  }
}
