package com.github.moxib.pomelo.seqsvr.rpc;

import com.github.moxib.pomelo.seqsvr.proto.Router;
import io.vertx.core.Future;

/**
 * StoreSvr 存储层访问接口 — AllocSvr / MediateSvr 读写持久化的统一抽象。
 * <p>
 * 实现：
 * <ul>
 *   <li>{@link EventBusStoreClient} — 跨进程 / 集群 EventBus RPC（生产）</li>
 *   <li>LocalStoreAccessor — 同进程内直接包装 StoreManager（单测 / 开发）</li>
 * </ul>
 * <p>
 * 对齐 Go StoreManager 的四个 RPC：loadMaxSeqsData / saveMaxSeq / loadRouteTable / saveRouteTable。
 */
public interface StoreAccessor {

  /**
   * 加载本 Set 全部 section 的 max_seq。
   */
  Future<long[]> loadMaxSeqsData();

  /**
   * 持久化某 id 所在 section 的 max_seq，返回存储对齐后的新 max_seq（向上取整到 SEQ_STEP 边界）。
   * 仅当新值大于旧值时写入，绝不回退。
   */
  Future<Long> saveMaxSeq(int id, long maxSeq);

  /**
   * 加载路由表。
   */
  Future<Router> loadRouteTable();

  /**
   * 保存路由表并落盘。
   */
  Future<Void> saveRouteTable(Router router);
}
