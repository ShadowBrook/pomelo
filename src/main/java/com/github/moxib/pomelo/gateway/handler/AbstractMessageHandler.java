package com.github.moxib.pomelo.gateway.handler;

import com.github.moxib.pomelo.codec.CodecRegistry;
import io.vertx.core.json.JsonObject;
import com.github.moxib.pomelo.codec.MessageCodec;
import com.github.moxib.pomelo.codec.ProtobufCodec;
import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.proto.common.CommonProto;
import com.github.moxib.pomelo.service.MessageRepository;
import com.github.moxib.pomelo.service.MessageService;
import com.github.moxib.pomelo.utils.IdGenerator;
import com.google.protobuf.Message;
import io.vertx.core.Vertx;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 消息处理器抽象基类。
 * 通过 {@link CodecRegistry} 统一 PB/JSON 编解码，Handler 使用 {@link #decodeRequest} 即可获得 codec-agnostic 的 DTO。
 */
public abstract class AbstractMessageHandler implements MessageHandler {

  protected final Vertx vertx;
  protected final CodecRegistry codecRegistry;
  protected final SessionRegistry sessionRegistry;
  protected final MessageRepository messageRepo;
  protected final MessageService messageService;
  protected final IdGenerator idGenerator;

  public AbstractMessageHandler(Vertx vertx, CodecRegistry codecRegistry,
                                 SessionRegistry sessionRegistry, MessageRepository messageRepo,
                                 MessageService messageService, IdGenerator idGenerator) {
    this.vertx = vertx;
    this.codecRegistry = codecRegistry;
    this.sessionRegistry = sessionRegistry;
    this.messageRepo = messageRepo;
    this.messageService = messageService;
    this.idGenerator = idGenerator;
  }

  // ================================================================
  // 响应发送
  // ================================================================

  protected void sendResponse(Connection connection, ImMessage response) {
    if (response != null) {
      connection.write(response.encodeToWire());
    }
  }

  /**
   * 发送错误响应。
   * 错误信息放在 body 中（codec 感知），cmd 保持为业务响应 cmd。
   *
   * @param responseCmd 业务响应 cmd（如 CMD_AUTH_RESP_VALUE）
   * @param error       错误码
   * @param detail      详细错误信息，为 null 时使用默认消息
   */
  protected void sendErrorResponse(Connection connection, ImMessage request,
                                    int responseCmd, ErrorCode error, String detail) {
    String msg = detail != null ? detail : error.getDefaultMessage();
    byte codecId = request.getCodecId();
    Object body = codecId == ProtobufCodec.CODEC_ID
      ? CommonProto.ErrorBody.newBuilder().setCode(error.getCode()).setMessage(msg).build()
      : new JsonObject().put("code", error.getCode()).put("message", msg);
    ImMessage response = buildResponse(request, responseCmd, body);
    sendResponse(connection, response);
  }

  // ================================================================
  // 统一请求解码（codec-agnostic）
  // ================================================================

  /**
   * 统一请求解码 — 根据 cmd + codecId 从 CodecRegistry 获取编解码器进行解码。
   * PB 路径返回 DTO record（经由 ProtobufCodec DTO 模式），JSON 路径返回同一 DTO record（经由 JsonCodec）。
   * Handler 代码不再需要区分 codec。
   */
  protected <T> T decodeRequest(ImMessage message, Class<T> dtoType) {
    MessageCodec<T> codec = codecRegistry.getCodec(message.getCmd(), message.getCodecId());
    if (codec == null) {
      throw new IllegalStateException(
        "No codec registered for cmd=" + message.getCmd()
        + " codecId=" + message.getCodecId() + " dtoType=" + dtoType.getSimpleName());
    }
    return codec.decode(message.getBody());
  }

  // ================================================================
  // 编码（与 decodeRequest 对称，优先走 CodecRegistry）
  // ================================================================

  /**
   * 编码消息体 — 与 {@link #decodeRequest} 对称，优先通过 CodecRegistry 查找编解码器。
   * 若未注册对应 codec 则 fallback：PB 走 {@link Message#toByteArray()}，JSON 走 Jackson。
   */
  protected byte[] encodeBody(byte codecId, int cmd, Object body) {
    MessageCodec<Object> codec = codecRegistry.getCodec(cmd, codecId);
    if (codec != null) {
      try {
        return codec.encode(body);
      } catch (UnsupportedOperationException ignored) {
      }
    }
    // Fallback
    if (codecId == ProtobufCodec.CODEC_ID && body instanceof Message msg) {
      return msg.toByteArray();
    }
    if (body instanceof JsonObject jo) {
      return jo.toBuffer().getBytes();
    }
    if (body != null) {
      return JsonObject.mapFrom(body).toBuffer().getBytes();
    }
    throw new RuntimeException("Cannot encode null body");
  }

  /** 获取请求消息体字符串 */
  protected String getBodyAsString(ImMessage message) {
    if (message.getBody() == null || message.getBody().length == 0) return null;
    return new String(message.getBody(), StandardCharsets.UTF_8);
  }

  // ================================================================
  // 推送消息构建
  // ================================================================

  /** 按 userId (NanoID) 查找 codec 构建推送消息 */
  protected ImMessage buildPushMessage(String targetUserId, int cmd, String messageId, Object body) {
    byte codecId = sessionRegistry.getCodecByUserId(targetUserId);
    return ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId(codecId)
      .cmd(cmd)
      .messageId(messageId)
      .body(encodeBody(codecId, cmd, body))
      .build();
  }

  /**
   * 构建响应消息，使用请求方的 codec。
   */
  protected ImMessage buildResponse(ImMessage request, int cmd, Object body) {
    byte codecId = request.getCodecId();
    return ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId(codecId)
      .cmd(cmd)
      .messageId(request.getMessageId())
      .body(encodeBody(codecId, cmd, body))
      .build();
  }

  /** 构建 JSON 格式的消息体 */
  protected JsonObject jsonBody() {
    return new JsonObject();
  }

  /** 从 varHeaders 提取 userId */
  protected String getUserIdFromHeaders(ImMessage message) {
    Map<String, String> headers = message.getVarHeaders();
    return headers != null ? headers.get("userId") : null;
  }
}
