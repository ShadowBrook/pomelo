package com.github.moxib.pomelo.gateway.handler;

import com.github.moxib.pomelo.common.ImMessage;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 聊天消息处理器
 * 处理客户端发送的聊天消息
 */
public class C2CMessageHandler extends AbstractMessageHandler {

  private static final Logger LOG = LoggerFactory.getLogger(C2CMessageHandler.class);

  public C2CMessageHandler(Vertx vertx) {
    super(vertx);
  }

  @Override
  public void handle(Connection connection, ImMessage message) {
    String body = getBodyAsString(message);
    String targetUserId = message.getVarHeaders() != null ? message.getVarHeaders().get("targetUserId") : null;

    LOG.info("收到聊天消息，targetUserId: {}, content: {}", targetUserId, body);

    // TODO: 实现具体的聊天消息处理逻辑，如消息存储、转发等


    // 构建聊天消息响应
    ImMessage response = ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId(message.getCodecId())
      .cmd(0x04) // 聊天消息响应 cmd
      .messageId(message.getMessageId())
      .varHeaders(java.util.Map.of("status", "success", "msgId", message.getMessageId()))
      .body("消息已发送".getBytes(java.nio.charset.StandardCharsets.UTF_8))
      .build();

    sendResponse(connection, response);
  }
}
