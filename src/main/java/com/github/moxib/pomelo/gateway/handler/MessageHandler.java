package com.github.moxib.pomelo.gateway.handler;

import com.github.moxib.pomelo.common.ImMessage;

/**
 * 消息处理器接口
 * 每个具体的业务处理器需要实现此接口
 */
public interface MessageHandler {

  /**
   * 处理消息
   *
   * @param connection 客户端连接
   * @param message 接收到的消息
   */
  void handle(Connection connection, ImMessage message);
}
