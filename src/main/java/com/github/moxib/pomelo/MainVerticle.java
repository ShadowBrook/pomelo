package com.github.moxib.pomelo;

import com.github.moxib.pomelo.gateway.WsGatewayVerticle;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;

public class MainVerticle extends VerticleBase {

  @Override
  public Future<?> start() {
    return vertx.deployVerticle(new WsGatewayVerticle());
  }

  public static void main(String[] args) {
    Vertx vertx = Vertx.vertx();
    vertx.deployVerticle(new MainVerticle());
  }
}
