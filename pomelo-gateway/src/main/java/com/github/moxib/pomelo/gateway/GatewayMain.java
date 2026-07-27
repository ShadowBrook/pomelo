package com.github.moxib.pomelo.gateway;

import com.github.moxib.pomelo.config.ConfigHolder;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gateway 独立启动入口。
 * 部署 TcpGatewayVerticle 和 WsGatewayVerticle。
 */
public class GatewayMain extends VerticleBase {

  private static final Logger LOG = LoggerFactory.getLogger(GatewayMain.class);

  @Override
  public Future<?> start() {
    return ConfigHolder.load(vertx)
      .compose(v -> Future.all(
        vertx.deployVerticle(new TcpGatewayVerticle()),
        vertx.deployVerticle(new WsGatewayVerticle())
      ))
      .mapEmpty();
  }

  public static void main(String[] args) {
    Vertx vertx = Vertx.vertx();
    vertx.deployVerticle(new GatewayMain())
      .onSuccess(id -> LOG.info("Gateway 已启动: deploymentId={}", id))
      .onFailure(e -> LOG.error("Gateway 启动失败", e));
  }
}
