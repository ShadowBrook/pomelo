package com.github.moxib.pomelo.logic;

import com.github.moxib.pomelo.config.ConfigHolder;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logic-Server 独立启动入口。
 * 部署 LogicVerticle（EventBus consumer）+ ApiVerticle（HTTP API）。
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
    Vertx vertx = Vertx.vertx();
    vertx.deployVerticle(new LogicMain())
      .onSuccess(id -> LOG.info("Logic-Server 已启动: deploymentId={}", id))
      .onFailure(e -> LOG.error("Logic-Server 启动失败", e));
  }
}
