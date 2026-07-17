package com.github.moxib.pomelo.gateway;

import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.gateway.handler.Connection;
import com.github.moxib.pomelo.gateway.handler.MessageDispatcher;
import com.github.moxib.pomelo.gateway.handler.SessionRegistry;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.VerticleBase;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import io.vertx.core.parsetools.RecordParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TCP 网关。
 */
public class TcpGatewayVerticle extends VerticleBase {

  private static final Logger LOG = LoggerFactory.getLogger(TcpGatewayVerticle.class);

  private static final int TCP_PORT = Integer.parseInt(System.getProperty("gateway.tcp.port", "9000"));

  private NetServer tcpServer;
  private MessageDispatcher dispatcher;

  @Override
  public Future<?> start() {
    LOG.info("启动 TCP Gateway，端口：{}", TCP_PORT);

    dispatcher = new MessageDispatcher(vertx);

    return dispatcher.start()
      .compose(v -> {
        tcpServer = vertx.createNetServer();
        return tcpServer.connectHandler(getTcpHandler()).listen(TCP_PORT);
      })
      .onSuccess(ar -> LOG.info("TCP Gateway 已启动，监听端口：{}", TCP_PORT))
      .onFailure(throwable -> LOG.error("TCP Gateway 启动失败", throwable));
  }

  @Override
  public Future<?> stop() {
    LOG.info("停止 TCP Gateway");
    return tcpServer != null ? tcpServer.close() : Future.succeededFuture();
  }

  private Handler<NetSocket> getTcpHandler() {
    SessionRegistry sessionRegistry = dispatcher.getSessionRegistry();

    return socket -> {
      RecordParser parser = RecordParser.newFixed(4);
      Connection conn = Connection.from(socket);

      Handler<Buffer> handler = new Handler<>() {
        int size = -1;

        @Override
        public void handle(Buffer buff) {
          if (size == -1) {
            size = buff.getInt(0);
            parser.fixedSizeMode(size);
          } else {
            ImMessage imMessage = new ImMessage();
            imMessage.readFromWire(buff);
            parser.fixedSizeMode(4);
            size = -1;
            dispatcher.dispatch(conn, imMessage);
          }
        }
      };

      parser.setOutput(handler);
      socket.handler(parser);

      socket.exceptionHandler(throwable -> {
        LOG.error("TCP 连接异常：{}", socket.remoteAddress(), throwable);
        sessionRegistry.unregisterByConnection(conn);
        socket.close();
      });

      socket.closeHandler(v -> {
        LOG.info("TCP 客户端断开连接：{}", socket.remoteAddress());
        sessionRegistry.unregisterByConnection(conn);
      });
    };
  }
}
