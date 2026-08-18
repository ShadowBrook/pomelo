package com.github.moxib.pomelo.seqsvr.client;

import com.github.moxib.pomelo.seqsvr.proto.Router;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * seqsvr 客户端 SDK — 供 Logic-Server / Gateway 调用。
 * <p>
 * - 本地缓存路由表 + 版本号
 * - 请求时携带本地版本号
 * - 响应中若携带新路由表则更新本地缓存
 */
public class SeqClientService {

  private static final Logger LOG = LoggerFactory.getLogger(SeqClientService.class);

  private final Vertx vertx;
  private volatile int routeVersion;
  private volatile Router cacheRouter;

  public SeqClientService(Vertx vertx) {
    this.vertx = vertx;
    this.routeVersion = 0;
  }

  /**
   * 获取下一个序列号。
   *
   * @param userId 用户 uid
   */
  public Future<Long> fetchNextSequence(long userId) {
    Promise<Long> promise = Promise.promise();
    JsonObject req = new JsonObject()
      .put("id", (int) (userId & 0xffffffffL))
      .put("version", routeVersion);

    vertx.eventBus().<JsonObject>request("seqsvr.alloc.fetchNext", req)
      .onComplete(ar -> {
        if (ar.succeeded()) {
          JsonObject resp = ar.result().body();
          processRouteInResponse(resp);
          promise.complete(resp.getLong("seq"));
        } else {
          LOG.warn("fetchNextSequence failed: userId={}, cause={}", userId, ar.cause().getMessage());
          promise.fail(ar.cause());
        }
      });

    return promise.future();
  }

  /**
   * 获取当前序列号（不递增）。
   */
  public Future<Long> getCurrentSequence(long userId) {
    Promise<Long> promise = Promise.promise();
    JsonObject req = new JsonObject()
      .put("id", (int) (userId & 0xffffffffL))
      .put("version", routeVersion);

    vertx.eventBus().<JsonObject>request("seqsvr.alloc.getCurrent", req)
      .onComplete(ar -> {
        if (ar.succeeded()) {
          JsonObject resp = ar.result().body();
          processRouteInResponse(resp);
          promise.complete(resp.getLong("seq"));
        } else {
          promise.fail(ar.cause());
        }
      });

    return promise.future();
  }

  /**
   * 处理响应中携带的路由表更新。
   * 对应 Go 版本响应中嵌入 Router 的逻辑。
   * 若服务端判定本客户端路由过期，会在响应中嵌入最新 Router，此处解析并缓存。
   */
  private void processRouteInResponse(JsonObject resp) {
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
}
