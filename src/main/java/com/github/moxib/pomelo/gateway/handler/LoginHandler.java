package com.github.moxib.pomelo.gateway.handler;

import com.github.moxib.pomelo.common.ImMessage;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 登录消息处理器
 * 处理客户端的登录请求
 */
public class LoginHandler extends AbstractMessageHandler {

  private static final Logger LOG = LoggerFactory.getLogger(LoginHandler.class);

  public LoginHandler(Vertx vertx) {
    super(vertx);
  }

  @Override
  public void handle(Connection connection, ImMessage message) {
    String body = getBodyAsString(message);
    LOG.info("收到登录请求，userId: {}", body);

    // TODO: 实现具体的登录逻辑，如验证用户凭证、获取用户信息等
    // 这里是示例响应

    // 构建登录响应
    ImMessage response = ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId(message.getCodecId())
      .cmd(0x02) // 登录响应 cmd
      .messageId(message.getMessageId())
      .varHeaders(java.util.Map.of("status", "success"))
      .body("登录成功".getBytes(java.nio.charset.StandardCharsets.UTF_8))
      .build();

    sendResponse(connection, response);
  }
}
