package com.github.moxib.pomelo.gateway;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Gateway 部署基本验证 — 不需要 Redis，仅验证 Gateway Verticle 正常启动。
 */
@ExtendWith(VertxExtension.class)
public class TestMainVerticle {

  @Test
  void gatewayVerticleDeploysSuccessfully(Vertx vertx, VertxTestContext ctx) {
    vertx.deployVerticle(new WsGatewayVerticle())
      .onComplete(ar -> {
        ctx.verify(() -> {
          if (ar.succeeded()) {
            vertx.undeploy(ar.result());
            ctx.completeNow();
          } else {
            ctx.failNow(ar.cause());
          }
        });
      });
  }
}
