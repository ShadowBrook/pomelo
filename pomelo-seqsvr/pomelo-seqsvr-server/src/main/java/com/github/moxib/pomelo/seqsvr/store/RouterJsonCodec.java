package com.github.moxib.pomelo.seqsvr.store;

import com.github.moxib.pomelo.seqsvr.proto.Router;
import io.vertx.core.json.JsonObject;

/**
 * Router ↔ JSON 编解码。
 */
final class RouterJsonCodec {

  private RouterJsonCodec() {}

  static String encode(Router router) {
    return JsonObject.mapFrom(router).encode();
  }

  static Router decode(String json) {
    return new JsonObject(json).mapTo(Router.class);
  }
}
