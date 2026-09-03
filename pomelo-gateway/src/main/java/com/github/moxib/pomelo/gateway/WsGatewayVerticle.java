package com.github.moxib.pomelo.gateway;

import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.config.ConfigHolder;
import com.github.moxib.pomelo.gateway.handler.Connection;
import com.github.moxib.pomelo.gateway.handler.MessageDispatcher;
import com.github.moxib.pomelo.gateway.handler.SessionRegistry;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.VerticleBase;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.ServerWebSocket;
import java.net.SocketException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebSocket 网关。
 */
public class WsGatewayVerticle extends VerticleBase {

  private static final Logger LOG = LoggerFactory.getLogger(WsGatewayVerticle.class);

  private int wsPort;
  private HttpServer wsServer;
  private SessionRegistry sessionRegistry;
  private MessageDispatcher dispatcher;

  @Override
  public Future<?> start() throws Exception {
    this.wsPort = ConfigHolder.getInt("gateway.websocket.port", 9001);
    long heartbeatTimeoutMs = ConfigHolder.getLong("gateway.heartbeat.timeoutMs", 90000L);
    this.sessionRegistry = new SessionRegistry();
    this.dispatcher = new MessageDispatcher(vertx, sessionRegistry, heartbeatTimeoutMs);

    // WebSocket 开启 permessage-deflate（RFC 7692）：
    // 浏览器在握手时自动协商，payload 在传输层压缩、应用层透明。
    // 对 JSON body 这类高重复键文本收益最大，且不改变现有二进制帧协议。
    boolean perMessageDeflate = ConfigHolder.getBoolean("gateway.websocket.perMessageDeflate", true);
    HttpServerOptions serverOptions = new HttpServerOptions()
      .setPerMessageWebSocketCompressionSupported(perMessageDeflate);
    wsServer = vertx.createHttpServer(serverOptions);
    return wsServer.webSocketHandler(getServerHandler()).listen(wsPort)
      .onSuccess(ar -> LOG.info("WebSocket 服务器已启动，监听端口：{}", wsPort))
      .onFailure(throwable -> LOG.error("WebSocket 服务器启动失败", throwable));
  }

  @Override
  public Future<?> stop() throws Exception {
    return Future.all(
      wsServer != null ? wsServer.close() : Future.succeededFuture(),
      dispatcher != null ? dispatcher.stop() : Future.succeededFuture()
    );
  }

  private Handler<ServerWebSocket> getServerHandler() {
    return ws -> {
      Connection conn = Connection.from(ws);

      ws.handler(buffer -> {
        ImMessage imMessage = new ImMessage();
        imMessage.readFromWire(buffer.getBuffer(4, buffer.length()));
        dispatcher.dispatch(conn, imMessage);
      });

      ws.closeHandler(closed -> {
        LOG.info("客户端断开连接：{}", ws.remoteAddress());
        String userId = sessionRegistry.unregisterByConnection(vertx, conn);
        dispatcher.getRouteTable().unregister(userId);
      });

      ws.exceptionHandler(throwable -> {
        if (throwable instanceof SocketException || throwable.getMessage().contains("Connection reset")) {
          LOG.debug("客户端连接重置：{}", ws.remoteAddress());
        } else {
          LOG.error("连接异常：{}", ws.remoteAddress(), throwable);
        }
        String userId = sessionRegistry.unregisterByConnection(vertx, conn);
        dispatcher.getRouteTable().unregister(userId);
        ws.close();
      });
    };
  }
}
