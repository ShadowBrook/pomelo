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
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.parsetools.RecordParser;
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

  public WsGatewayVerticle() {
  }

  /**
   * 共享会话组件构造：TCP 与 WS 部署在同一节点时必须共用同一
   * SessionRegistry/MessageDispatcher，否则同地址的双 push consumer
   * 会把消息轮询投递到没有该用户会话的一侧，造成推送丢失。
   */
  public WsGatewayVerticle(SessionRegistry sessionRegistry, MessageDispatcher dispatcher) {
    this.sessionRegistry = sessionRegistry;
    this.dispatcher = dispatcher;
  }

  @Override
  public Future<?> start() throws Exception {
    this.wsPort = ConfigHolder.getInt("gateway.websocket.port", 9001);
    if (sessionRegistry == null) {
      long heartbeatTimeoutMs = ConfigHolder.getLong("gateway.heartbeat.timeoutMs", 90000L);
      this.sessionRegistry = new SessionRegistry(vertx);
      this.dispatcher = new MessageDispatcher(vertx, sessionRegistry, heartbeatTimeoutMs);
    }

    // protobuf使用deflate收益不高
    HttpServerOptions serverOptions = new HttpServerOptions()
      .setPerMessageWebSocketCompressionSupported(false);
    // TLS：启用后客户端需使用 wss://
    PemKeyCertOptions pem = TlsConfig.pemKeyCert();
    if (pem != null) {
      serverOptions.setSsl(true).setKeyCertOptions(pem);
    }
    wsServer = vertx.createHttpServer(serverOptions);
    return wsServer.webSocketHandler(getServerHandler()).listen(wsPort)
      .onSuccess(ar -> LOG.info("WebSocket 服务器已启动，监听端口：{}（TLS={}）", wsPort, TlsConfig.enabled()))
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
      // WS 二进制帧与 TCP 共用同一 4 字节长度前缀协议，
      // 统一走 RecordParser：正确处理帧分片与粘包，避免对整帧到达的错误假设
      RecordParser parser = RecordParser.newFixed(4);

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
              LOG.warn("非法帧长度 {}，断开连接: {}", size, ws.remoteAddress());
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
              LOG.warn("帧解析失败，断开连接: {} cause={}", ws.remoteAddress(), e.getMessage());
              broken = true;
              conn.close();
            }
          }
        }
      };

      parser.setOutput(handler);
      ws.handler(parser);

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
