package com.github.moxib.pomelo.gateway.handler;

import com.github.moxib.pomelo.common.ImMessage;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.*;

/**
 * 消息分发器
 * 根据 cmd 将消息分发到对应的处理器
 */
public class MessageDispatcher {

  private static final Logger LOG = LoggerFactory.getLogger(MessageDispatcher.class);

  private final Vertx vertx;
  private final Map<Integer, Supplier<MessageHandler>> handlerRegistry;

  public MessageDispatcher(Vertx vertx) {
    this.vertx = vertx;
    this.handlerRegistry = new HashMap<>();
    // 注册默认处理器
    registerDefaultHandlers();
  }

  /**
   * 注册默认处理器
   */
  private void registerDefaultHandlers() {
    // 心跳请求
    register(CMD_PING_VALUE, () -> new HeartbeatHandler(vertx));
    // 登录请求
    register(CMD_AUTH_REQ_VALUE, () -> new LoginHandler(vertx));
    // 登出请求
    register(CMD_LOGOUT_REQ_VALUE, () -> new LogoutHandler(vertx));
    // 单聊请求
    register(CMD_C2C_REQ_VALUE, () -> new C2CMessageHandler(vertx));
    // 群聊请求
    register(CMD_C2G_REQ_VALUE, () -> new C2GMessageHandler(vertx));
    // 控制命令请求
    register(CMD_CTRL_REQ_VALUE, () -> new CtrlReqHandler(vertx));
    // ACK 消息确认请求
    register(CMD_ACK_REQ_VALUE, () -> new AckReqHandler(vertx));
  }

  /**
   * 注册消息处理器
   *
   * @param cmd 命令字
   * @param handlerSupplier 处理器供应商
   */
  public void register(int cmd, Supplier<MessageHandler> handlerSupplier) {
    handlerRegistry.put(cmd, handlerSupplier);
    LOG.debug("注册 cmd={} 的处理器：{}", cmd, handlerSupplier.get().getClass().getSimpleName());
  }

  /**
   * 分发并处理消息
   *
   * @param connection  客户端连接
   * @param message 消息
   */
  public void dispatch(Connection connection, ImMessage message) {
    int cmd = message.getCmd();
    Supplier<MessageHandler> handlerSupplier = handlerRegistry.get(cmd);

    if (handlerSupplier == null) {
      LOG.warn("未找到 cmd={} 的处理器", cmd);
      handleUnknownCmd(connection, message);
      return;
    }

    try {
      MessageHandler handler = handlerSupplier.get();
      handler.handle(connection, message);
    } catch (Exception e) {
      LOG.warn("处理消息时发生错误，cmd={}", cmd);
      handleException(connection, message, e);
    }
  }

  /**
   * 处理未知 cmd
   */
  private void handleUnknownCmd(Connection connection, ImMessage request) {
    ImMessage response = ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId(request.getCodecId())
      .cmd(0xFF) // 错误响应
      .messageId(request.getMessageId())
      .body(("Unknown cmd: " + request.getCmd()).getBytes(java.nio.charset.StandardCharsets.UTF_8))
      .build();
    connection.write(response.encodeToWire());
  }

  /**
   * 处理异常情况
   */
  private void handleException(Connection connection, ImMessage request, Exception e) {
    ImMessage response = ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId(request.getCodecId())
      .cmd(0xFE) // 错误响应
      .messageId(request.getMessageId())
      .body(("Error: " + e.getMessage()).getBytes(java.nio.charset.StandardCharsets.UTF_8))
      .build();
    connection.write(response.encodeToWire());
  }
}
