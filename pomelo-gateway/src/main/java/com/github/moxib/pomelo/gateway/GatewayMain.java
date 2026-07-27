package com.github.moxib.pomelo.gateway;

import com.github.moxib.pomelo.config.ClusterHelper;
import com.github.moxib.pomelo.config.ConfigHolder;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gateway 启动入口。
 *
 * 本地模式（默认）：
 *   java -jar pomelo-gateway.jar
 *
 * 集群模式：
 *   java -Dvertx.cluster=true -jar pomelo-gateway.jar
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
    Vertx vertx = ClusterHelper.createVertx();
    vertx.deployVerticle(new GatewayMain())
      .onSuccess(id -> LOG.info("Gateway started: deploymentId={}, clustered={}", id, vertx.isClustered()))
      .onFailure(e -> LOG.error("Gateway startup failed", e));
  }
}
