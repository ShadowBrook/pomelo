package com.github.moxib.pomelo.gateway.handler;

import com.github.moxib.pomelo.codec.CodecRegistry;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.service.MessageRepository;
import com.github.moxib.pomelo.service.MessageService;
import com.github.moxib.pomelo.utils.IdGenerator;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 登出消息处理器。
 * 处理客户端登出请求，清理 Session。
 */
public class LogoutHandler extends AbstractMessageHandler {

  private static final Logger LOG = LoggerFactory.getLogger(LogoutHandler.class);

  public LogoutHandler(Vertx vertx, CodecRegistry codecRegistry,
                        SessionRegistry sessionRegistry, MessageRepository messageRepo,
                        MessageService messageService, IdGenerator idGenerator) {
    super(vertx, codecRegistry, sessionRegistry, messageRepo, messageService, idGenerator);
  }

  @Override
  public void handle(Connection connection, ImMessage message) {
    String userId = getBodyAsString(message);
    Map<String, String> headers = message.getVarHeaders();
    if (headers != null && headers.containsKey("userId")) {
      userId = headers.get("userId");
    }

    LOG.info("收到登出请求，userId: {}", userId);

    // 注销用户会话
    if (userId != null && !userId.isEmpty()) {
      sessionRegistry.unregister(userId);
    } else {
      sessionRegistry.unregisterByConnection(connection);
    }

    ImMessage response = ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId(message.getCodecId())
      .cmd(0x04)
      .messageId(message.getMessageId())
      .varHeaders(Map.of("status", "success"))
      .body("登出成功".getBytes(StandardCharsets.UTF_8))
      .build();

    sendResponse(connection, response);
  }
}
