package com.github.moxib.pomelo.gateway;

import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.gateway.handler.Connection;
import com.github.moxib.pomelo.gateway.handler.MessageDispatcher;
import com.github.moxib.pomelo.gateway.handler.SessionRegistry;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.VerticleBase;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.ServerWebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebSocket 网关。
 */
public class WsGatewayVerticle extends VerticleBase {

  private static final Logger LOG = LoggerFactory.getLogger(WsGatewayVerticle.class);

  private static final int WS_PORT = Integer.parseInt(System.getProperty("gateway.websocket.port", "9001"));

  private HttpServer wsServer;
  private MessageDispatcher dispatcher;

  @Override
  public Future<?> start() throws Exception {
    dispatcher = new MessageDispatcher(vertx);

    return dispatcher.start()
      .compose(v -> {
        wsServer = vertx.createHttpServer();
        return wsServer.webSocketHandler(getServerHandler()).listen(WS_PORT);
      })
      .onSuccess(ar -> LOG.info("WebSocket 服务器已启动，监听端口：{}", WS_PORT))
      .onFailure(throwable -> LOG.error("WebSocket 服务器启动失败", throwable));
  }

  @Override
  public Future<?> stop() throws Exception {
    return wsServer != null ? wsServer.close() : Future.succeededFuture();
  }

  private Handler<ServerWebSocket> getServerHandler() {
    SessionRegistry sessionRegistry = dispatcher.getSessionRegistry();

    return ws -> {
      Connection conn = Connection.from(ws);

      ws.handler(buffer -> {
        ImMessage imMessage = new ImMessage();
        imMessage.readFromWire(buffer.getBuffer(4, buffer.length()));
        dispatcher.dispatch(conn, imMessage);
      });

      ws.closeHandler(closed -> {
        LOG.info("客户端断开连接：{}", ws.remoteAddress());
        sessionRegistry.unregisterByConnection(conn);
      });

      ws.exceptionHandler(throwable -> {
        LOG.error("连接异常：{}", ws.remoteAddress(), throwable);
        sessionRegistry.unregisterByConnection(conn);
        ws.close();
      });
    };
  }
}
