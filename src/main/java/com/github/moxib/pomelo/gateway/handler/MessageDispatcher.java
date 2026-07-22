package com.github.moxib.pomelo.gateway.handler;

import io.vertx.core.json.JsonObject;
import com.github.moxib.pomelo.codec.CodecRegistry;
import com.github.moxib.pomelo.codec.MessageCodec;
import com.github.moxib.pomelo.codec.ProtobufCodec;
import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.proto.common.CommonProto;
import com.google.protobuf.Message;
import com.github.moxib.pomelo.proto.ack.AckProto;
import com.github.moxib.pomelo.proto.auth.AuthProto;
import com.github.moxib.pomelo.proto.chat.ChatProto;
import com.github.moxib.pomelo.proto.ctrl.CtrlProto;
import com.github.moxib.pomelo.proto.pull.PullProto;
import com.github.moxib.pomelo.proto.relation.RelationProto;
import com.github.moxib.pomelo.service.MessageRepository;
import com.github.moxib.pomelo.service.MessageService;
import com.github.moxib.pomelo.service.MessageServiceImpl;
import com.github.moxib.pomelo.service.PgMessageRepository;
import com.github.moxib.pomelo.service.RedisFactory;
import com.github.moxib.pomelo.service.model.requests.*;
import com.github.moxib.pomelo.utils.IdGenerator;
import com.github.moxib.pomelo.utils.RedisIdGenerator;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    this.idGenerator = new RedisIdGenerator(vertx, RedisFactory.get(vertx).getRedis());
    this.messageService = new MessageServiceImpl(messageRepo, sessionRegistry, idGenerator);

    registerCodecs();
    registerDefaultHandlers();
  }


  /**
   * 初始化需要异步资源的组件。
   * RedisIdGenerator 的 tryInit 延迟到首次 nextId() 调用时执行，不阻塞启动。
   */
  public Future<Void> start() {
    Promise<Void> promise = Promise.promise();
    RedisFactory.get(vertx).connect().onComplete(ar -> {
      if (ar.succeeded()) {
        LOG.info("RedisFactory + IdGenerator 已初始化");
        promise.complete();
      } else {
        LOG.error("启动初始化失败", ar.cause());
        promise.fail(ar.cause());
      }
    });
    return promise.future();
  }

  /**
   * 注册所有编解码器到 CodecRegistry。
   * PB codecId=0：ProtobufCodec DTO 模式（PB bytes → DTO record）
   * JSON codecId=1：JsonCodec（JSON bytes → DTO record）
   */
  private void registerCodecs() {
    // Auth
    codecRegistry.registerProtobuf(CMD_AUTH_REQ_VALUE, AuthProto.AuthReq.parser(), LoginRequest::fromProto, LoginRequest.class);
    codecRegistry.registerJson(CMD_AUTH_REQ_VALUE, LoginRequest.class);

    // C2C
    codecRegistry.registerProtobuf(CMD_C2C_REQ_VALUE, ChatProto.C2CReq.parser(), C2CRequest::fromProto, C2CRequest.class);
    codecRegistry.registerJson(CMD_C2C_REQ_VALUE, C2CRequest.class);

    // Ctrl
    codecRegistry.registerProtobuf(CMD_CTRL_REQ_VALUE, CtrlProto.CtrlReq.parser(), CtrlRequest::fromProto, CtrlRequest.class);
    codecRegistry.registerJson(CMD_CTRL_REQ_VALUE, CtrlRequest.class);

    // ACK
    codecRegistry.registerProtobuf(CMD_ACK_REQ_VALUE, AckProto.AckReq.parser(), AckRequest::fromProto, AckRequest.class);
    codecRegistry.registerJson(CMD_ACK_REQ_VALUE, AckRequest.class);

    // Pull
    codecRegistry.registerProtobuf(CMD_PULL_REQ_VALUE, PullProto.PullReq.parser(), PullRequest::fromProto, PullRequest.class);
    codecRegistry.registerJson(CMD_PULL_REQ_VALUE, PullRequest.class);

    // Friend: Search
    codecRegistry.registerProtobuf(CMD_FRIEND_SEARCH_REQ_VALUE, RelationProto.SearchUserReq.parser(), SearchRequest::fromProto, SearchRequest.class);
    codecRegistry.registerJson(CMD_FRIEND_SEARCH_REQ_VALUE, SearchRequest.class);

    // Friend: Add
    codecRegistry.registerProtobuf(CMD_FRIEND_ADD_REQ_VALUE, RelationProto.FriendAddReq.parser(), FriendOpRequest::fromAddProto, FriendOpRequest.class);
    codecRegistry.registerJson(CMD_FRIEND_ADD_REQ_VALUE, FriendOpRequest.class);

    // Friend: Accept
    codecRegistry.registerProtobuf(CMD_FRIEND_ACCEPT_REQ_VALUE, RelationProto.FriendAcceptReq.parser(), FriendOpRequest::fromAcceptProto, FriendOpRequest.class);
    codecRegistry.registerJson(CMD_FRIEND_ACCEPT_REQ_VALUE, FriendOpRequest.class);

    // Friend: Delete
    codecRegistry.registerProtobuf(CMD_FRIEND_DELETE_REQ_VALUE, RelationProto.FriendDeleteReq.parser(), FriendOpRequest::fromDeleteProto, FriendOpRequest.class);
    codecRegistry.registerJson(CMD_FRIEND_DELETE_REQ_VALUE, FriendOpRequest.class);
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

    // 好友关系（共用同一个 Handler 实例，根据 cmd 分发）
    FriendHandler friendHandler = new FriendHandler(vertx, codecRegistry, sessionRegistry, messageRepo, messageService, idGenerator);
    register(CMD_FRIEND_SEARCH_REQ_VALUE, () -> friendHandler);
    register(CMD_FRIEND_ADD_REQ_VALUE, () -> friendHandler);
    register(CMD_FRIEND_ACCEPT_REQ_VALUE, () -> friendHandler);
    register(CMD_FRIEND_DELETE_REQ_VALUE, () -> friendHandler);
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
    String msg = "Unknown cmd: 0x" + Integer.toHexString(request.getCmd());
    byte codecId = request.getCodecId();
    ImMessage response = ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId(codecId)
      .cmd(CMD_ERROR_VALUE)
      .messageId(request.getMessageId())
      .body(encodeErrorBody(codecId, ErrorCode.UNKNOWN_CMD.getCode(), msg))
      .build();
    connection.write(response.encodeToWire());
  }

  /** 处理异常 */
  private void handleException(Connection connection, ImMessage request, Exception e) {
    byte codecId = request.getCodecId();
    ImMessage response = ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId(codecId)
      .cmd(CMD_ERROR_VALUE)
      .messageId(request.getMessageId())
      .body(encodeErrorBody(codecId, ErrorCode.INTERNAL_ERROR.getCode(), e.getMessage()))
      .build();
    connection.write(response.encodeToWire());
  }


  /** 编码错误响应体 — 通过 CodecRegistry 或 fallback PB/JSON */
  @SuppressWarnings("unchecked")
  private byte[] encodeErrorBody(byte codecId, int errorCode, String message) {
    var codec = (MessageCodec<Object>) codecRegistry.getCodec(CMD_ERROR_VALUE, codecId);
    Object body;
    if (codecId == 0) {
      body = CommonProto.ErrorBody.newBuilder().setCode(errorCode).setMessage(message).build();
    } else {
      body = new JsonObject().put("code", errorCode).put("message", message);
    }
    if (codec != null) {
      try { return codec.encode(body); }
      catch (UnsupportedOperationException ignored) {}
    }
    // Fallback
    if (body instanceof Message msg) return msg.toByteArray();
    return ((JsonObject) body).toBuffer().getBytes();
  }

  /** 获取 SessionRegistry（供 Gateway Verticle 在断连时清理） */
  public SessionRegistry getSessionRegistry() {
    return sessionRegistry;
  }
}
