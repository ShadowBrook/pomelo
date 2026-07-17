package com.github.moxib.pomelo.gateway.handler;

import com.github.moxib.pomelo.codec.CodecRegistry;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.service.MessageRepository;
import com.github.moxib.pomelo.service.MessageService;
import com.github.moxib.pomelo.service.MessageServiceImpl;
import com.github.moxib.pomelo.service.PgMessageRepository;
import com.github.moxib.pomelo.utils.IdGenerator;
import com.github.moxib.pomelo.utils.RedisIdGenerator;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.*;

/**
 * 消息分发器 — 持有所有共享依赖，创建并注入到各 Handler。
 */
public class MessageDispatcher {

  private static final Logger LOG = LoggerFactory.getLogger(MessageDispatcher.class);

  private final Vertx vertx;
  private final Map<Integer, Supplier<MessageHandler>> handlerRegistry;

  // 共享依赖（单例，全 Handler 共享）
  private final CodecRegistry codecRegistry;
  private final SessionRegistry sessionRegistry;
  private final MessageRepository messageRepo;
  private final MessageService messageService;
  private final IdGenerator idGenerator;

  public MessageDispatcher(Vertx vertx) {
    this.vertx = vertx;
    this.handlerRegistry = new HashMap<>();

    // 初始化共享依赖
    this.codecRegistry = new CodecRegistry();
    this.sessionRegistry = new SessionRegistry();
    this.messageRepo = new PgMessageRepository(vertx);
    this.idGenerator = new RedisIdGenerator(vertx);
    this.messageService = new MessageServiceImpl(messageRepo, sessionRegistry, idGenerator);

    registerDefaultHandlers();
  }

  /**
   * 初始化需要异步资源的组件（如 RedisIdGenerator）。
   * 必须在 start() 完成后才能 dispatch 消息。
   */
  public Future<Void> start() {
    return idGenerator.tryInit(0, 5000)
      .onSuccess(v -> LOG.info("IdGenerator 已初始化"))
      .onFailure(e -> LOG.error("IdGenerator 初始化失败", e))
      .mapEmpty();
  }

  /**
   * 注册默认处理器。
   */
  private void registerDefaultHandlers() {
    register(CMD_PING_VALUE, () ->
      new HeartbeatHandler(vertx, codecRegistry, sessionRegistry, messageRepo, messageService, idGenerator));

    register(CMD_AUTH_REQ_VALUE, () ->
      new LoginHandler(vertx, codecRegistry, sessionRegistry, messageRepo, messageService, idGenerator));

    register(CMD_LOGOUT_REQ_VALUE, () ->
      new LogoutHandler(vertx, codecRegistry, sessionRegistry, messageRepo, messageService, idGenerator));

    register(CMD_C2C_REQ_VALUE, () ->
      new C2CMessageHandler(vertx, codecRegistry, sessionRegistry, messageRepo, messageService, idGenerator));

    register(CMD_C2G_REQ_VALUE, () ->
      new C2GMessageHandler(vertx, codecRegistry, sessionRegistry, messageRepo, messageService, idGenerator));

    register(CMD_CTRL_REQ_VALUE, () ->
      new CtrlReqHandler(vertx, codecRegistry, sessionRegistry, messageRepo, messageService, idGenerator));

    register(CMD_ACK_REQ_VALUE, () ->
      new AckReqHandler(vertx, codecRegistry, sessionRegistry, messageRepo, messageService, idGenerator));

    register(CMD_PULL_REQ_VALUE, () ->
      new PullMessageHandler(vertx, codecRegistry, sessionRegistry, messageRepo, messageService, idGenerator));
  }

  /**
   * 注册消息处理器。
   */
  public void register(int cmd, Supplier<MessageHandler> handlerSupplier) {
    handlerRegistry.put(cmd, handlerSupplier);
    LOG.debug("注册 cmd={} 的处理器", cmd);
  }

  /**
   * 分发并处理消息。
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
      LOG.warn("处理消息时发生错误，cmd={}", cmd, e);
      handleException(connection, message, e);
    }
  }

  /** 处理未知 cmd */
  private void handleUnknownCmd(Connection connection, ImMessage request) {
    ImMessage response = ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId(request.getCodecId())
      .cmd(0xFF)
      .messageId(request.getMessageId())
      .body(("Unknown cmd: " + request.getCmd()).getBytes(StandardCharsets.UTF_8))
      .build();
    connection.write(response.encodeToWire());
  }

  /** 处理异常 */
  private void handleException(Connection connection, ImMessage request, Exception e) {
    ImMessage response = ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId(request.getCodecId())
      .cmd(0xFE)
      .messageId(request.getMessageId())
      .body(("Error: " + e.getMessage()).getBytes(StandardCharsets.UTF_8))
      .build();
    connection.write(response.encodeToWire());
  }

  /** 获取 SessionRegistry（供 Gateway Verticle 在断连时清理） */
  public SessionRegistry getSessionRegistry() {
    return sessionRegistry;
  }
}
