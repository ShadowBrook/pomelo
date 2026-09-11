package com.github.moxib.pomelo.gateway;

import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Gateway 部署基本验证 — 不需要 Redis，仅验证 Gateway Verticle 正常启动。
 * 使用独立测试端口，避免与其他测试/本机服务冲突。
 */
@ExtendWith(VertxExtension.class)
public class TestMainVerticle {

  @BeforeAll
  static void setUpPort() {
    System.setProperty("gateway.websocket.port", "19101");
  }

  @AfterAll
  static void clearPort() {
    System.clearProperty("gateway.websocket.port");
  }

  @Test
  void gatewayVerticleDeploysSuccessfully(Vertx vertx, VertxTestContext ctx) {
    // 显式等待 undeploy 与 Vertx 关闭完成：避免与本 JVM 内后续测试类的
    // Vertx 生命周期重叠（会导致下一个测试的首次连接得不到响应）
    vertx.deployVerticle(new WsGatewayVerticle())
      .compose(id -> vertx.undeploy(id))
      .onComplete(ar -> {
        if (ar.succeeded()) {
          ctx.completeNow();
        } else {
          ctx.failNow(ar.cause());
        }
      });
  }
}
