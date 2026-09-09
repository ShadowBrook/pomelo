package com.github.moxib.pomelo.logic;

import com.github.moxib.pomelo.config.ClusterHelper;
import com.github.moxib.pomelo.config.ConfigHolder;
import com.github.moxib.pomelo.logic.id.SnowflakeIdGenerator;
import com.github.moxib.pomelo.logic.id.WorkerIdResolver;
import com.github.moxib.pomelo.logic.infrastructure.RedisFactory;
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
    ClusterHelper.warnIfNotClustered(vertx, "Logic-Server");
    return ConfigHolder.load(vertx)
      .compose(v -> RedisFactory.get(vertx).connect())
      .compose(v -> WorkerIdResolver.resolve(vertx))
      .compose(workerId -> {
        SnowflakeIdGenerator snowflake = new SnowflakeIdGenerator(workerId);
        LOG.info("workerId 已解析: {}", workerId);
        return Future.all(
          vertx.deployVerticle(new LogicVerticle(snowflake)),
          vertx.deployVerticle(new ApiVerticle(snowflake))
        );
      })
      .mapEmpty();
  }

  public static void main(String[] args) {
    Vertx vertx = ClusterHelper.createVertx();
    vertx.deployVerticle(new LogicMain())
      .onSuccess(id -> LOG.info("Logic-Server started: deploymentId={}, clustered={}", id, vertx.isClustered()))
      .onFailure(e -> LOG.error("Logic-Server startup failed", e));
  }
}
