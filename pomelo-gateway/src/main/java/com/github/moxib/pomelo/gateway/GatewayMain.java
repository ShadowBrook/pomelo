package com.github.moxib.pomelo.gateway;

import com.github.moxib.pomelo.config.ClusterHelper;
import com.github.moxib.pomelo.config.ConfigHolder;
import com.github.moxib.pomelo.gateway.handler.MessageDispatcher;
import com.github.moxib.pomelo.gateway.handler.SessionRegistry;
import com.github.moxib.pomelo.metrics.PomeloMetrics;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
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

  private HttpServer metricsServer;

  @Override
  public Future<?> start() {
    ClusterHelper.warnIfNotClustered(vertx, "Gateway");
    return ConfigHolder.load(vertx)
      .compose(v -> {
        // TCP/WS 共享同一会话组件：路由与 push consumer 按节点注册，
        // 双套组件会导致 push 被轮询投递到没有用户会话的一侧而丢失
        long heartbeatTimeoutMs = ConfigHolder.getLong("gateway.heartbeat.timeoutMs", 90000L);
        SessionRegistry registry = new SessionRegistry(vertx);
        PomeloMetrics.gauge("im.connections", registry, SessionRegistry::size);
        MessageDispatcher dispatcher = new MessageDispatcher(vertx, registry, heartbeatTimeoutMs);
        return Future.all(
          vertx.deployVerticle(new TcpGatewayVerticle(registry, dispatcher)),
          vertx.deployVerticle(new WsGatewayVerticle(registry, dispatcher))
        ).compose(v2 -> startMetricsServer());
      })
      .mapEmpty();
  }

  /** Prometheus 指标端点（gateway.metrics.port，默认 10104；<=0 关闭） */
  private Future<Void> startMetricsServer() {
    int port = ConfigHolder.getInt("gateway.metrics.port", 10104);
    if (port <= 0) {
      return Future.succeededFuture();
    }
    metricsServer = vertx.createHttpServer();
    metricsServer.requestHandler(req -> {
      if ("/metrics".equals(req.path())) {
        req.response()
          .putHeader("content-type", "text/plain; version=0.0.4; charset=utf-8")
          .end(PomeloMetrics.scrape());
      } else {
        req.response().setStatusCode(404).end();
      }
    });
    return metricsServer.listen(port)
      .onSuccess(v -> LOG.info("Gateway metrics server started on port {}", port))
      .mapEmpty();
  }

  @Override
  public Future<?> stop() {
    return metricsServer != null ? metricsServer.close() : Future.succeededFuture();
  }

  public static void main(String[] args) {
    Vertx vertx = ClusterHelper.createVertx(PomeloMetrics.vertxMetricsFactory());
    vertx.deployVerticle(new GatewayMain())
      .onSuccess(id -> LOG.info("Gateway started: deploymentId={}, clustered={}", id, vertx.isClustered()))
      .onFailure(e -> {
        // 启动失败必须退出：否则进程残留为"半启动节点"，既不服务也不释放资源
        LOG.error("Gateway startup failed", e);
        vertx.close().onComplete(ar -> System.exit(1));
      });
  }
}
