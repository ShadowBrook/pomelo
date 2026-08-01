package com.github.moxib.pomelo.seqsvr.rpc;

import com.github.moxib.pomelo.seqsvr.proto.Router;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * NRW 多副本 StoreClient — 同一 Set 的 M 个 StoreSvr 副本（M ≥ 3）。
 * <p>
 * 语义（租约保证任意 section 任意时刻只有一个写入者，副本间无并发写冲突，max 合并即正确）：
 * <ul>
 *   <li>写（saveMaxSeq / saveRouteTable）：发给全部副本，需 W 个确认（W ≥ 2）</li>
 *   <li>读（loadMaxSeqsData）：从 R 个副本读取，各 section 取最大值（R ≥ 2）</li>
 *   <li>loadRouteTable：从 R 个副本读取，取 version 最大者</li>
 * </ul>
 * <p>
 * 每个副本一个地址前缀（如 {@code seqsvr.store.r1} / {@code seqsvr.store.r2} / {@code seqsvr.store.r3}），
 * 由 StoreVerticle 按 replicaId 注册。
 */
public class ReplicatedStoreClient implements StoreAccessor {

  private static final long REQUEST_TIMEOUT_MS = 5000;

  private final EventBus eventBus;
  private final List<String> replicaPrefixes;
  private final int w;
  private final int r;

  public ReplicatedStoreClient(EventBus eventBus, List<String> replicaPrefixes, int w, int r) {
    if (replicaPrefixes == null || replicaPrefixes.isEmpty()) {
      throw new IllegalArgumentException("replicaPrefixes must not be empty");
    }
    this.eventBus = eventBus;
    this.replicaPrefixes = replicaPrefixes;
    this.w = w;
    this.r = r;
  }

  private Future<JsonObject> requestReplica(String prefix, String op, JsonObject body) {
    return eventBus.request(prefix + "." + op, body, new DeliveryOptions().setSendTimeout(REQUEST_TIMEOUT_MS))
      .map(msg -> (JsonObject) msg.body());
  }

  /**
   * 向全部副本发请求，凑够 required 个成功即完成；不足 required 则失败。
   */
  private <T> Future<List<T>> quorum(List<Future<T>> futures, int required, String op) {
    if (futures.isEmpty() || required <= 0 || required > futures.size()) {
      return Future.failedFuture(new IllegalStateException(
        "quorum misconfigured for " + op + ": futures=" + futures.size() + ", required=" + required));
    }
    Promise<List<T>> promise = Promise.promise();
    List<T> ok = new ArrayList<>();
    int[] done = {0};
    Object lock = new Object();
    int n = futures.size();
    for (Future<T> f : futures) {
      f.onComplete(ar -> {
        synchronized (lock) {
          if (ar.succeeded()) {
            ok.add(ar.result());
          }
          done[0]++;
          if (ok.size() >= required) {
            promise.tryComplete(new ArrayList<>(ok));
          } else if (done[0] == n) {
            promise.tryFail(new IllegalStateException(
              "quorum not met for " + op + ": got " + ok.size() + "/" + required));
          }
        }
      });
    }
    return promise.future();
  }

  @Override
  public Future<long[]> loadMaxSeqsData() {
    List<Future<JsonObject>> futures = new ArrayList<>(replicaPrefixes.size());
    for (String prefix : replicaPrefixes) {
      futures.add(requestReplica(prefix, "loadMaxSeqsData", new JsonObject()));
    }
    return quorum(futures, r, "loadMaxSeqsData").map(replies -> {
      JsonArray first = replies.get(0).getJsonArray("maxSeqs");
      long[] out = new long[first.size()];
      for (int i = 0; i < first.size(); i++) {
        out[i] = first.getLong(i);
      }
      // 各副本取 max 合并
      for (int k = 1; k < replies.size(); k++) {
        JsonArray arr = replies.get(k).getJsonArray("maxSeqs");
        for (int i = 0; i < out.length && i < arr.size(); i++) {
          long v = arr.getLong(i);
          if (v > out[i]) {
            out[i] = v;
          }
        }
      }
      return out;
    });
  }

  @Override
  public Future<Long> saveMaxSeq(int id, long maxSeq) {
    JsonObject body = new JsonObject().put("id", id).put("maxSeq", maxSeq);
    List<Future<JsonObject>> futures = new ArrayList<>(replicaPrefixes.size());
    for (String prefix : replicaPrefixes) {
      futures.add(requestReplica(prefix, "saveMaxSeq", body));
    }
    return quorum(futures, w, "saveMaxSeq").map(replies -> {
      long max = 0;
      for (JsonObject reply : replies) {
        long v = reply.getLong("v");
        if (v > max) {
          max = v;
        }
      }
      return max;
    });
  }

  @Override
  public Future<Router> loadRouteTable() {
    List<Future<JsonObject>> futures = new ArrayList<>(replicaPrefixes.size());
    for (String prefix : replicaPrefixes) {
      futures.add(requestReplica(prefix, "loadRouteTable", new JsonObject()));
    }
    return quorum(futures, r, "loadRouteTable").map(replies -> {
      Router best = replies.get(0).mapTo(Router.class);
      for (int i = 1; i < replies.size(); i++) {
        Router rt = replies.get(i).mapTo(Router.class);
        if (rt.getVersion() > best.getVersion()) {
          best = rt;
        }
      }
      return best;
    });
  }

  @Override
  public Future<Void> saveRouteTable(Router router) {
    List<Future<JsonObject>> futures = new ArrayList<>(replicaPrefixes.size());
    for (String prefix : replicaPrefixes) {
      futures.add(requestReplica(prefix, "saveRouteTable", JsonObject.mapFrom(router)));
    }
    return quorum(futures, w, "saveRouteTable").mapEmpty();
  }
}
