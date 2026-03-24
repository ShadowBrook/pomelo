package com.github.moxib.pomelo.gateway.handler;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;

/**
 * 统一的连接接口
 * 封装 NetSocket 和 ServerWebSocket 的共同行为
 */
public interface Connection {

  /**
   * 发送数据
   *
   * @param buffer 要发送的数据
   * @return Future 表示发送结果
   */
  Future<Void> write(Buffer buffer);

  /**
   * 获取远程地址
   *
   * @return SocketAddress
   */
  SocketAddress remoteAddress();

  /**
   * 关闭连接
   */
  void close();

  /**
   * 从 NetSocket 创建 Connection
   *
   * @param netSocket TCP 连接
   * @return Connection 实例
   */
  static Connection from(NetSocket netSocket) {
    return new NetSocketConnection(netSocket);
  }

  /**
   * 从 ServerWebSocket 创建 Connection
   *
   * @param webSocket WebSocket 连接
   * @return Connection 实例
   */
  static Connection from(ServerWebSocket webSocket) {
    return new WebSocketConnection(webSocket);
  }

  /**
   * NetSocket 实现
   */
  class NetSocketConnection implements Connection {
    private final NetSocket netSocket;

    public NetSocketConnection(NetSocket netSocket) {
      this.netSocket = netSocket;
    }

    @Override
    public Future<Void> write(Buffer buffer) {
      return netSocket.write(buffer);
    }

    @Override
    public SocketAddress remoteAddress() {
      return netSocket.remoteAddress();
    }

    @Override
    public void close() {
      netSocket.close();
    }
  }

  /**
   * ServerWebSocket 实现
   */
  class WebSocketConnection implements Connection {
    private final ServerWebSocket webSocket;

    public WebSocketConnection(ServerWebSocket webSocket) {
      this.webSocket = webSocket;
    }

    @Override
    public Future<Void> write(Buffer buffer) {
      return webSocket.writeBinaryMessage(buffer);
    }

    @Override
    public SocketAddress remoteAddress() {
      return webSocket.remoteAddress();
    }

    @Override
    public void close() {
      webSocket.close();
    }
  }
}
