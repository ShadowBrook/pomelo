package com.github.moxib.pomelo.gateway.handler;

import com.github.moxib.pomelo.common.ImMessage;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 登出消息处理器
 * 处理客户端的登出请求
 */
public class LogoutHandler extends AbstractMessageHandler {

  private static final Logger LOG = LoggerFactory.getLogger(LogoutHandler.class);

  public LogoutHandler(Vertx vertx) {
    super(vertx);
  }

  @Override
  public void handle(Connection connection, ImMessage message) {
    LOG.info("收到登出请求，sessionId: {}", connection.remoteAddress());

    // TODO: 实现具体的登出逻辑，如清理用户会话、通知其他服务等

    // 构建登出响应
    ImMessage response = ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId(message.getCodecId())
      .cmd(0x04) // 登出响应 cmd
      .messageId(message.getMessageId())
      .varHeaders(java.util.Map.of("status", "success"))
      .body("登出成功".getBytes(java.nio.charset.StandardCharsets.UTF_8))
      .build();

    sendResponse(connection, response);
  }
}
