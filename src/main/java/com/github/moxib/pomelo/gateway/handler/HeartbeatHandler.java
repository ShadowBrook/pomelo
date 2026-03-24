package com.github.moxib.pomelo.gateway.handler;

import com.github.moxib.pomelo.common.ImMessage;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.CMD_PONG_VALUE;

/**
 * 心跳消息处理器
 * 处理客户端的心跳请求
 */
public class HeartbeatHandler extends AbstractMessageHandler {

  private static final Logger LOG = LoggerFactory.getLogger(HeartbeatHandler.class);

  public HeartbeatHandler(Vertx vertx) {
    super(vertx);
  }

  @Override
  public void handle(Connection connection, ImMessage message) {
    LOG.debug("收到心跳消息，sessionId: {}", connection.remoteAddress());

    // 构建心跳响应
    ImMessage pong = ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId(message.getCodecId())
      .cmd(CMD_PONG_VALUE)
      .messageId(message.getMessageId())
      .body(null)
      .build();
    sendResponse(connection, pong);
  }
}
