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
    this.sessionRegistry = new SessionRegistry();
    this.dispatcher = new MessageDispatcher(vertx, sessionRegistry);

    wsServer = vertx.createHttpServer();
    return wsServer.webSocketHandler(getServerHandler()).listen(wsPort)
      .onSuccess(ar -> LOG.info("WebSocket 服务器已启动，监听端口：{}", wsPort))
      .onFailure(throwable -> LOG.error("WebSocket 服务器启动失败", throwable));
  }

  @Override
  public Future<?> stop() throws Exception {
    return wsServer != null ? wsServer.close() : Future.succeededFuture();
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
        String userId = sessionRegistry.unregisterByConnection(conn);
        dispatcher.getRouteTable().unregister(userId);
      });

      ws.exceptionHandler(throwable -> {
        if (throwable instanceof SocketException || throwable.getMessage().contains("Connection reset")) {
          LOG.debug("客户端连接重置：{}", ws.remoteAddress());
        } else {
          LOG.error("连接异常：{}", ws.remoteAddress(), throwable);
        }
        String userId = sessionRegistry.unregisterByConnection(conn);
        dispatcher.getRouteTable().unregister(userId);
        ws.close();
      });
    };
  }
}
