package com.github.moxib.pomelo.seqsvr.rpc;

import com.github.moxib.pomelo.seqsvr.proto.Router;
import io.vertx.core.Future;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * StoreSvr 的 EventBus RPC 客户端。
 * <p>
 * 请求 / 响应格式：
 * <ul>
 *   <li>loadMaxSeqsData → {@code { maxSeqs: long[] }}</li>
 *   <li>saveMaxSeq({id, maxSeq}) → {@code { v: long }}（存储对齐后的新 max_seq）</li>
 *   <li>loadRouteTable → Router JSON</li>
 *   <li>saveRouteTable(Router) → {@code { ok: true }}</li>
 * </ul>
 */
public class EventBusStoreClient implements StoreAccessor {

  private static final long REQUEST_TIMEOUT_MS = 10_000;

  private final EventBus eventBus;
  private final String prefix;

  public EventBusStoreClient(EventBus eventBus) {
    this(eventBus, SeqSvrAddresses.STORE_PREFIX);
  }

  public EventBusStoreClient(EventBus eventBus, String prefix) {
    this.eventBus = eventBus;
    this.prefix = prefix;
  }

  private String address(String op) {
    return prefix + "." + op;
  }

  private DeliveryOptions options() {
    return new DeliveryOptions().setSendTimeout(REQUEST_TIMEOUT_MS);
  }

  @Override
  public Future<long[]> loadMaxSeqsData() {
    return eventBus.request(address("loadMaxSeqsData"), new JsonObject(), options())
      .map(msg -> {
        JsonArray arr = ((JsonObject) msg.body()).getJsonArray("maxSeqs");
        long[] out = new long[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
          out[i] = arr.getLong(i);
        }
        return out;
      });
  }

  @Override
  public Future<Long> saveMaxSeq(int id, long maxSeq) {
    JsonObject req = new JsonObject().put("id", id).put("maxSeq", maxSeq);
    return eventBus.request(address("saveMaxSeq"), req, options())
      .map(msg -> ((JsonObject) msg.body()).getLong("v"));
  }

  @Override
  public Future<Router> loadRouteTable() {
    return eventBus.request(address("loadRouteTable"), new JsonObject(), options())
      .map(msg -> ((JsonObject) msg.body()).mapTo(Router.class));
  }

  @Override
  public Future<Void> saveRouteTable(Router router) {
    return eventBus.request(address("saveRouteTable"), JsonObject.mapFrom(router), options())
      .mapEmpty();
  }
}
