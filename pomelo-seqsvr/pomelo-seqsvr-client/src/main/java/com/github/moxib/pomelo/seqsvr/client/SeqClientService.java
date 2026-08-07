package com.github.moxib.pomelo.seqsvr.client;

import com.github.moxib.pomelo.seqsvr.proto.RangeId;
import com.github.moxib.pomelo.seqsvr.proto.Router;
import com.github.moxib.pomelo.seqsvr.proto.RouterNode;
import com.github.moxib.pomelo.seqsvr.proto.SeqSvrConstants;
import com.github.moxib.pomelo.seqsvr.rpc.SeqSvrAddresses;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * seqsvr 客户端 SDK — 供 Logic-Server / Gateway 调用。
 * <p>
 * 对应文章"路由同步优化"四步：
 * <ol>
 *   <li>本地缓存路由表 → 按 sectionRanges 匹配目标 AllocSvr；无路由表时走兜底地址</li>
 *   <li>请求携带本地 version</li>
 *   <li>收到响应：若带 router 且版本更新 → 更新缓存</li>
 *   <li>失败重试（最多一次，避免重试风暴）：
 *       - 服务端返回 ALLOC_CODE_ROUTE_OUTDATED（本节点不拥有该号段）→ 用新路由表重路由重试；
 *       - 传输失败（目标节点不可达，如故障迁移中节点已下线）→ 走共享兜底地址重试一次</li>
 * </ol>
 */
public class SeqClientService {

  private static final Logger LOG = LoggerFactory.getLogger(SeqClientService.class);

  /** 最多重试一次 */
  private static final int MAX_RETRY = 1;

  private final Vertx vertx;
  // id 哈希空间，须与服务端 maxIdSize 对齐（测试可缩小；生产默认 PRODUCTION_MAX_ID_SIZE）
  private final int maxIdSize;
  private volatile int routeVersion;
  private volatile Router cacheRouter;

  public SeqClientService(Vertx vertx) {
    this(vertx, SeqSvrConstants.PRODUCTION_MAX_ID_SIZE);
  }

  public SeqClientService(Vertx vertx, int maxIdSize) {
    this.vertx = vertx;
    this.maxIdSize = maxIdSize;
    this.routeVersion = 0;
  }

  /**
   * 获取下一个序列号。
   *
   * @param userId 用户 uid
   */
  public Future<Long> fetchNextSequence(long userId) {
    return attempt(toSectionId(userId, maxIdSize), true, 0);
  }

  /**
   * 按字符串 key 获取下一个序列号（群聊等非数值 ID 场景）。
   * 内部将字符串确定性哈希为 long 后走标准分配路径。
   */
  public Future<Long> fetchNextSequenceByKey(String key) {
    return attempt(toSectionId(toLongKey(key), maxIdSize), true, 0);
  }

  /**
   * 将字符串 key 确定性哈希为 64-bit 非负 long。
   * 使用类似 toSectionId 的最终化步骤，保证同 key 结果稳定。
   */
  static long toLongKey(String key) {
    long h = 0;
    for (int i = 0; i < key.length(); i++) {
      h = h * 31 + key.charAt(i);
    }
    // 最终化：扩散高位信息到低位，避免连续 key 的 section 碰撞
    h ^= (h >>> 33);
    h *= 0xff51afd7ed558ccdL;
    h ^= (h >>> 33);
    h *= 0xc4ceb9fe1a85ec53L;
    h ^= (h >>> 33);
    return h < 0 ? -(h + 1) : h;
  }

  /**
   * 获取当前序列号（不递增）。
   */
  public Future<Long> getCurrentSequence(long userId) {
    return attempt(toSectionId(userId, maxIdSize), false, 0);
  }

  private Future<Long> attempt(int id, boolean increment, int attempt) {
    String address = routeAddress(id, increment);
    return send(address, id)
      .compose(resp -> resolveResponse(resp, id, increment, attempt))
      .recover(err -> fallbackOnTransportFailure(id, increment, attempt, err));
  }

  private Future<Long> resolveResponse(JsonObject resp, int id, boolean increment, int attempt) {
    int code = resp.getInteger("code", SeqSvrConstants.ALLOC_CODE_OK);
    if (code == SeqSvrConstants.ALLOC_CODE_ROUTE_OUTDATED) {
      int versionBefore = routeVersion;
      updateRouteFrom(resp);
      if (attempt >= MAX_RETRY) {
        // 路由表有实质更新
        if (routeVersion > versionBefore) {
          LOG.info("Route outdated but route updated (v{}→v{}), retrying: id={}",
            versionBefore, routeVersion, id);
          return attempt(id, increment, attempt + 1);
        }
        return Future.failedFuture(new IllegalStateException("route outdated after retry: " + resp.getString("message")));
      }
      // 路由表已更新，重路由重试
      return attempt(id, increment, attempt + 1);
    }
    processRouteInResponse(resp);
    return Future.succeededFuture(resp.getLong("seq"));
  }

  private Future<Long> fallbackOnTransportFailure(int id, boolean increment, int attempt, Throwable err) {
    // 业务可判定失败（路由过期重试耗尽）不再兜底
    if (err instanceof IllegalStateException) {
      return Future.failedFuture(err);
    }
    if (attempt >= MAX_RETRY) {
      return Future.failedFuture(err);
    }
    // 目标节点不可达（故障迁移中下线）：走共享兜底地址重试一次
    LOG.debug("node-scoped request failed, falling back to shared address: id={}, cause={}",
      id, err.getMessage());
    String shared = increment ? SeqSvrAddresses.ALLOC_FETCH_NEXT : SeqSvrAddresses.ALLOC_GET_CURRENT;
    return send(shared, id).compose(resp -> {
      int code = resp.getInteger("code", SeqSvrConstants.ALLOC_CODE_OK);
      if (code == SeqSvrConstants.ALLOC_CODE_ROUTE_OUTDATED) {
        updateRouteFrom(resp);
        // 路由表已更新，用新路由重试
        LOG.info("Route outdated from fallback, retrying with updated route: id={}", id);
        return attempt(id, increment, attempt + 1);
      }
      processRouteInResponse(resp);
      return Future.succeededFuture(resp.getLong("seq"));
    });
  }

  private Future<JsonObject> send(String address, int id) {
    JsonObject req = new JsonObject()
      .put("id", id)
      .put("version", routeVersion);
    return vertx.eventBus().<JsonObject>request(address, req).map(Message::body);
  }

  /**
   * 按本地路由表把 id 路由到拥有该号段的 AllocSvr 节点；无路由表 / 找不到归属节点时走兜底地址。
   */
  private String routeAddress(int id, boolean increment) {
    Router r = cacheRouter;
    if (r != null) {
      String nodeId = owningNode(r, id);
      if (nodeId != null) {
        return increment ? SeqSvrAddresses.allocNodeFetchNext(nodeId)
          : SeqSvrAddresses.allocNodeGetCurrent(nodeId);
      }
    }
    return increment ? SeqSvrAddresses.ALLOC_FETCH_NEXT : SeqSvrAddresses.ALLOC_GET_CURRENT;
  }

  private static String owningNode(Router router, int id) {
    for (RouterNode node : router.getNodeList()) {
      for (RangeId range : node.getSectionRanges()) {
        if (range.calcSectionID(id).isFound()) {
          return node.getNodeId();
        }
      }
    }
    return null;
  }

  /**
   * 将业务 key 确定性哈希到正数 id 空间（默认全量空间）。
   */
  static int toSectionId(long key) {
    return toSectionId(key, SeqSvrConstants.PRODUCTION_MAX_ID_SIZE);
  }

  /**
   * 将业务 key 确定性哈希到 [0, maxIdSize) 内的正数 id。
   * 原实现对 snowflake 用户 id 截断低 32 位，约一半用户会得到负数 id 被服务端拒绝；
   * 改为哈希后 id 恒非负且落在服务端 id 空间内，且同一 key 结果稳定（保证该 key 的 seq 单调）。
   */
  static int toSectionId(long key, int maxIdSize) {
    long h = key;
    h ^= (h >>> 33);
    h *= 0xff51afd7ed558ccdL;
    h ^= (h >>> 33);
    h *= 0xc4ceb9fe1a85ec53L;
    h ^= (h >>> 33);
    return Math.floorMod(h, maxIdSize);
  }

  private void processRouteInResponse(JsonObject resp) {
    updateRouteFrom(resp);
  }

  private void updateRouteFrom(JsonObject resp) {
    JsonObject routerJson = resp.getJsonObject("router");
    if (routerJson == null) {
      return;
    }
    Router newRouter = routerJson.mapTo(Router.class);
    if (newRouter.getVersion() > routeVersion) {
      cacheRouter = newRouter;
      routeVersion = newRouter.getVersion();
      LOG.info("Route table updated: version={}, nodes={}",
        routeVersion, newRouter.getNodeList().size());
    }
  }

  // ==================== 路由表缓存 ====================

  public int getRouteVersion() { return routeVersion; }

  public void setRouteVersion(int version) { this.routeVersion = version; }

  /** 获取缓存的路由表（多节点路由决策时使用） */
  public Router getRouter() { return cacheRouter; }

  /** 注入路由表（冷启动引导 / 测试） */
  public void setRouter(Router router) {
    this.cacheRouter = router;
    this.routeVersion = router.getVersion();
  }
}
