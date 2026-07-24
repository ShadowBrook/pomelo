package com.github.moxib.pomelo;

import com.github.moxib.pomelo.api.ApiVerticle;
import com.github.moxib.pomelo.config.ConfigHolder;
import com.github.moxib.pomelo.gateway.WsGatewayVerticle;
import com.github.moxib.pomelo.service.PgPoolFactory;
import com.github.moxib.pomelo.service.RedisFactory;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;

public class MainVerticle extends VerticleBase {

  @Override
  public Future<?> start() {
    return ConfigHolder.load(vertx)
      .compose(v -> Future.all(
        vertx.deployVerticle(new WsGatewayVerticle()),
        vertx.deployVerticle(new ApiVerticle())
      ))
      .mapEmpty();
  }

  @Override
  public Future<?> stop() {
    PgPoolFactory.close();
    RedisFactory.get(vertx).close();
    return Future.succeededFuture();
  }

  public static void main(String[] args) {
    Vertx vertx = Vertx.vertx();
    vertx.deployVerticle(new MainVerticle());
  }
}
