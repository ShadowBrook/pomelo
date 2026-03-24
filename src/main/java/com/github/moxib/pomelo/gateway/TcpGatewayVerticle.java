package com.github.moxib.pomelo.gateway;

import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.gateway.handler.Connection;
import com.github.moxib.pomelo.gateway.handler.MessageDispatcher;
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
 * TCP 网关
 */
public class TcpGatewayVerticle extends VerticleBase {

  private static final Logger LOG = LoggerFactory.getLogger(TcpGatewayVerticle.class);

  // TCP 服务端口
  private static final int TCP_PORT = Integer.parseInt(System.getProperty("gateway.tcp.port", "9000"));

  private NetServer tcpServer;
  private MessageDispatcher dispatcher;

  @Override
  public Future<?> start() {
    LOG.info("启动 GatewayVerticle，端口：{}", TCP_PORT);

    // 初始化消息分发器
    dispatcher = new MessageDispatcher(vertx);

    // 创建 TCP 服务器
    tcpServer = vertx.createNetServer();

    return tcpServer
      .connectHandler(getTcpHandler())
      .listen(TCP_PORT)
      .onSuccess(ar -> LOG.info("TCP gateway 服务器已启动，监听端口：{}", TCP_PORT))
      .onFailure(throwable -> LOG.error("TCP gateway 服务器启动失败", throwable));
  }

  @Override
  public Future<?> stop() {
    LOG.info("停止 GatewayVerticle");
    return tcpServer != null ? tcpServer.close() : Future.succeededFuture();
  }

  /**
   * 处理 TCP 客户端连接
   */
  private Handler<NetSocket> getTcpHandler() {
    return socket -> {
      RecordParser parser = RecordParser.newFixed(4);
      Handler<Buffer> handler = new Handler<>() {
        int size = -1;

        public void handle(Buffer buff) {
          if (size == -1) {
            size = buff.getInt(0);
            parser.fixedSizeMode(size);
          } else {
            ImMessage imMessage = new ImMessage();
            imMessage.readFromWire(buff);
            parser.fixedSizeMode(4);
            size = -1;
            dispatcher.dispatch(Connection.from(socket), imMessage);
          }
        }
      };

      parser.setOutput(handler);
      socket.handler(parser);

      socket.exceptionHandler(throwable -> {
        LOG.error("TCP 连接异常：{}", socket.remoteAddress(), throwable);
        socket.close();
      });

      socket.closeHandler(v -> {
        LOG.info("TCP 客户端断开连接：{}", socket.remoteAddress());
      });

    };
  }
}
