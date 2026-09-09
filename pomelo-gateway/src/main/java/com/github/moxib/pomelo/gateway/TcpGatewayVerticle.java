package com.github.moxib.pomelo.gateway;

import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.config.ConfigHolder;
import com.github.moxib.pomelo.config.TlsConfig;
import com.github.moxib.pomelo.gateway.handler.Connection;
import com.github.moxib.pomelo.gateway.handler.MessageDispatcher;
import com.github.moxib.pomelo.gateway.handler.SessionRegistry;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.VerticleBase;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.parsetools.RecordParser;
import java.net.SocketException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TCP 网关。
 */
public class TcpGatewayVerticle extends VerticleBase {

  private static final Logger LOG = LoggerFactory.getLogger(TcpGatewayVerticle.class);

  private int tcpPort;
  private NetServer tcpServer;
  private SessionRegistry sessionRegistry;
  private MessageDispatcher dispatcher;

  public TcpGatewayVerticle() {
  }

  /**
   * 共享会话组件构造：TCP 与 WS 部署在同一节点时必须共用同一
   * SessionRegistry/MessageDispatcher，否则同地址的双 push consumer
   * 会把消息轮询投递到没有该用户会话的一侧，造成推送丢失。
   */
  public TcpGatewayVerticle(SessionRegistry sessionRegistry, MessageDispatcher dispatcher) {
    this.sessionRegistry = sessionRegistry;
    this.dispatcher = dispatcher;
  }

  @Override
  public Future<?> start() {
    this.tcpPort = ConfigHolder.getInt("gateway.tcp.port", 9000);
    LOG.info("启动 TCP Gateway，端口：{}", tcpPort);

    if (sessionRegistry == null) {
      long heartbeatTimeoutMs = ConfigHolder.getLong("gateway.heartbeat.timeoutMs", 90000L);
      this.sessionRegistry = new SessionRegistry(vertx);
      this.dispatcher = new MessageDispatcher(vertx, sessionRegistry, heartbeatTimeoutMs);
    }
    // TLS：启用后客户端需以 TLS 握手连接
    NetServerOptions serverOptions = new NetServerOptions();
    PemKeyCertOptions pem = TlsConfig.pemKeyCert();
    if (pem != null) {
      serverOptions.setSsl(true).setKeyCertOptions(pem);
    }
    tcpServer = vertx.createNetServer(serverOptions);

    return tcpServer
      .connectHandler(getTcpHandler())
      .listen(tcpPort)
      .onSuccess(ar -> LOG.info("TCP Gateway 已启动，监听端口：{}（TLS={}）", tcpPort, TlsConfig.enabled()))
      .onFailure(throwable -> LOG.error("TCP Gateway 启动失败", throwable));
  }

  @Override
  public Future<?> stop() {
    LOG.info("停止 TCP Gateway");
    return Future.all(
      tcpServer != null ? tcpServer.close() : Future.succeededFuture(),
      dispatcher != null ? dispatcher.stop() : Future.succeededFuture()
    );
  }

  private Handler<NetSocket> getTcpHandler() {
    return socket -> {
      LOG.debug("TCP 客户端已连接：{}", socket.remoteAddress());
      RecordParser parser = RecordParser.newFixed(4);
      Connection conn = Connection.from(socket);

      Handler<Buffer> handler = new Handler<>() {
        int size = -1;
        boolean broken = false;

        @Override
        public void handle(Buffer buff) {
          if (broken) {
            return;
          }
          if (size == -1) {
            size = buff.getInt(0);
            if (size < ImMessage.MIN_FRAME_LENGTH || size > ImMessage.MAX_FRAME_SIZE) {
              // 长度前缀非法即协议违规：断连，防止恶意长度声明拖垮内存
              LOG.warn("非法帧长度 {}，断开连接: {}", size, socket.remoteAddress());
              broken = true;
              conn.close();
              return;
            }
            parser.fixedSizeMode(size);
          } else {
            try {
              ImMessage imMessage = new ImMessage();
              imMessage.readFromWire(buff);
              parser.fixedSizeMode(4);
              size = -1;
              dispatcher.dispatch(conn, imMessage);
            } catch (Exception e) {
              // 解析失败视为协议违规：连接字节流已不可信，断连
              LOG.warn("帧解析失败，断开连接: {} cause={}", socket.remoteAddress(), e.getMessage());
              broken = true;
              conn.close();
            }
          }
        }
      };

      parser.setOutput(handler);
      socket.handler(parser);

      socket.exceptionHandler(throwable -> {
        if (throwable instanceof SocketException || throwable.getMessage().contains("Connection reset")) {
          LOG.debug("TCP 连接重置：{}", socket.remoteAddress());
        } else {
          LOG.error("TCP 连接异常：{}", socket.remoteAddress(), throwable);
        }
        String userId = sessionRegistry.unregisterByConnection(conn);
        dispatcher.getRouteTable().unregister(userId);
        socket.close();
      });

      socket.closeHandler(v -> {
        LOG.info("TCP 客户端断开连接：{}", socket.remoteAddress());
        String userId = sessionRegistry.unregisterByConnection(conn);
        dispatcher.getRouteTable().unregister(userId);
      });
    };
  }
}
