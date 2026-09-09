package com.github.moxib.pomelo.gateway;

import com.github.moxib.pomelo.config.ClusterHelper;
import com.github.moxib.pomelo.config.ConfigHolder;
import com.github.moxib.pomelo.gateway.handler.MessageDispatcher;
import com.github.moxib.pomelo.gateway.handler.SessionRegistry;
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
    ClusterHelper.warnIfNotClustered(vertx, "Gateway");
    return ConfigHolder.load(vertx)
      .compose(v -> {
        // TCP/WS 共享同一会话组件：路由与 push consumer 按节点注册，
        // 双套组件会导致 push 被轮询投递到没有用户会话的一侧而丢失
        long heartbeatTimeoutMs = ConfigHolder.getLong("gateway.heartbeat.timeoutMs", 90000L);
        SessionRegistry registry = new SessionRegistry(vertx);
        MessageDispatcher dispatcher = new MessageDispatcher(vertx, registry, heartbeatTimeoutMs);
        return Future.all(
          vertx.deployVerticle(new TcpGatewayVerticle(registry, dispatcher)),
          vertx.deployVerticle(new WsGatewayVerticle(registry, dispatcher))
        );
      })
      .mapEmpty();
  }

  public static void main(String[] args) {
    Vertx vertx = ClusterHelper.createVertx();
    vertx.deployVerticle(new GatewayMain())
      .onSuccess(id -> LOG.info("Gateway started: deploymentId={}, clustered={}", id, vertx.isClustered()))
      .onFailure(e -> LOG.error("Gateway startup failed", e));
  }
}
