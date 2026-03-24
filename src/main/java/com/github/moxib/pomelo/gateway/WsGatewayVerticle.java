package com.github.moxib.pomelo.gateway;

import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.gateway.handler.Connection;
import com.github.moxib.pomelo.gateway.handler.MessageDispatcher;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.VerticleBase;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.ServerWebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * websocket 网关
 */
public class WsGatewayVerticle extends VerticleBase {

  private static final Logger LOG = LoggerFactory.getLogger(WsGatewayVerticle.class);

  // WEBSOCKET 服务端口
  private static final int WS_PORT = Integer.parseInt(System.getProperty("gateway.websocket.port", "9001"));

  HttpServer wsServer;

  private MessageDispatcher dispatcher;

  @Override
  public Future<?> start() throws Exception {

    // 初始化消息分发器
    dispatcher = new MessageDispatcher(vertx);

    wsServer = vertx.createHttpServer();

    return wsServer.webSocketHandler(getServerHandler())
      .listen(WS_PORT)
      .onSuccess(ar -> LOG.info("WEBSOCKET 服务器已启动，监听端口：{}", WS_PORT))
      .onFailure(throwable -> LOG.error("WEBSOCKET 服务器启动失败", throwable));
  }

  @Override
  public Future<?> stop() throws Exception {
    return wsServer != null ? wsServer.close() : Future.succeededFuture();
  }

  private Handler<ServerWebSocket> getServerHandler() {
    return ws -> {
      ws.handler(buffer -> {
        ImMessage imMessage = new ImMessage();
        imMessage.readFromWire(buffer.getBuffer(4, buffer.length()));
        dispatcher.dispatch(Connection.from(ws), imMessage);
      });
      ws.closeHandler(closed -> {
        LOG.info("客户端断开连接：{}", ws.remoteAddress().toString());
      });

      ws.exceptionHandler(throwable -> {
        LOG.error("连接异常：{}", ws.remoteAddress().toString(), throwable);
        ws.close();
      });
    };
  }
}
