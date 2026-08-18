package com.github.moxib.pomelo.logic;

import com.github.moxib.pomelo.config.ClusterHelper;
import com.github.moxib.pomelo.config.ConfigHolder;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logic-Server 启动入口。
 *
 * 本地模式（默认）：
 *   java -jar pomelo-logic-server.jar
 *
 * 集群模式：
 *   java -Dvertx.cluster=true -jar pomelo-logic-server.jar
 */
public class LogicMain extends VerticleBase {

  private static final Logger LOG = LoggerFactory.getLogger(LogicMain.class);

  @Override
  public Future<?> start() {
    return ConfigHolder.load(vertx)
      .compose(v -> Future.all(
        vertx.deployVerticle(new LogicVerticle()),
        vertx.deployVerticle(new ApiVerticle())
      ))
      .mapEmpty();
  }

  public static void main(String[] args) {
    Vertx vertx = ClusterHelper.createVertx();
    vertx.deployVerticle(new LogicMain())
      .onSuccess(id -> LOG.info("Logic-Server started: deploymentId={}, clustered={}", id, vertx.isClustered()))
      .onFailure(e -> LOG.error("Logic-Server startup failed", e));
  }
}
