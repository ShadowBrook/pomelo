package com.github.moxib.pomelo.gateway.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.moxib.pomelo.codec.CodecRegistry;
import com.github.moxib.pomelo.codec.ProtobufCodec;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.service.MessageRepository;
import com.github.moxib.pomelo.service.MessageService;
import com.github.moxib.pomelo.utils.IdGenerator;
import com.google.protobuf.Message;
import io.vertx.core.Vertx;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * 消息处理器抽象基类。
 * 支持 Protobuf（codecId=0）和 JSON（codecId=1）双协议。
 */
public abstract class AbstractMessageHandler implements MessageHandler {

  protected final Vertx vertx;
  protected final CodecRegistry codecRegistry;
  protected final SessionRegistry sessionRegistry;
  protected final MessageRepository messageRepo;
  protected final MessageService messageService;
  protected final IdGenerator idGenerator;
  protected static final ObjectMapper MAPPER = new ObjectMapper();

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

  protected void sendErrorResponse(Connection connection, ImMessage request, int errorCode, String errorMsg) {
    ImMessage response = ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId(request.getCodecId())
      .cmd(errorCode)
      .messageId(request.getMessageId())
      .body(errorMsg.getBytes(StandardCharsets.UTF_8))
      .build();
    sendResponse(connection, response);
  }

  // ================================================================
  // 编解码工具
  // ================================================================

  /** 根据 codecId 编码消息体：Protobuf → bytes，JSON → JSON bytes */
  protected byte[] encodeBody(byte codecId, Object messageOrProto) {
    if (codecId == ProtobufCodec.CODEC_ID) {
      return ((Message) messageOrProto).toByteArray();
    } else {
      try {
        return MAPPER.writeValueAsBytes(messageOrProto);
      } catch (JsonProcessingException e) {
        throw new RuntimeException("JSON encode failed", e);
      }
    }
  }

  /** 根据 codecId 解码消息体 */
  @SuppressWarnings("unchecked")
  protected <T extends Message> T decodeBodyByCodec(byte codecId, int cmd, byte[] body, Class<T> protoClass) {
    if (codecId == ProtobufCodec.CODEC_ID) {
      ProtobufCodec<T> codec =
        (ProtobufCodec<T>) codecRegistry.getProtobufCodec(cmd);
      return codec.decode(body);
    } else {
      try {
        return MAPPER.readValue(body, protoClass);
      } catch (Exception e) {
        throw new RuntimeException("JSON decode failed for " + protoClass.getSimpleName(), e);
      }
    }
  }

  /** 获取请求消息体字符串 */
  protected String getBodyAsString(ImMessage message) {
    if (message.getBody() == null || message.getBody().length == 0) return null;
    return new String(message.getBody(), StandardCharsets.UTF_8);
  }

  /** Protobuf 解码（反射 parseFrom） */
  @SuppressWarnings("unchecked")
  protected <T extends Message> T decodeProtobuf(byte[] data, Class<T> messageType) {
    try {
      Method method = messageType.getMethod("parseFrom", byte[].class);
      return (T) method.invoke(null, data);
    } catch (Exception e) {
      throw new RuntimeException("Failed to decode protobuf", e);
    }
  }

  /** Protobuf 编码 */
  protected byte[] encodeProtobuf(Message message) {
    return message.toByteArray();
  }

  /** JSON 解码 */
  protected JsonNode parseJsonBody(ImMessage message) {
    try {
      return MAPPER.readTree(message.getBody());
    } catch (Exception e) {
      throw new RuntimeException("Failed to parse JSON body", e);
    }
  }

  /** 根据请求的 codecId 自动解码 Protobuf 消息体 */
  protected <T extends Message> T decodeBody(ImMessage message) {
    if (message.getCodecId() == ProtobufCodec.CODEC_ID) {
      ProtobufCodec<T> codec =
        codecRegistry.getProtobufCodec(message.getCmd());
      return codec.decode(message.getBody());
    } else {
      throw new UnsupportedOperationException("JSON auto-decode not yet supported; use parseJsonBody()");
    }
  }

  // ================================================================
  // 推送消息构建
  // ================================================================

  /**
   * 构建 ImMessage 推送给目标用户，使用目标用户的 codec。
   * @param targetUserId 目标用户 ID（从 SessionRegistry 查 codec）
   * @param cmd 命令字
   * @param messageId 消息 ID
   * @param body 消息体（Protobuf Message 或 Jackson-serializable POJO）
   */
  protected ImMessage buildPushMessage(String targetUserId, int cmd, String messageId, Object body) {
    byte codecId = sessionRegistry.getCodec(targetUserId);
    return ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId(codecId)
      .cmd(cmd)
      .messageId(messageId)
      .body(encodeBody(codecId, body))
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
      .body(encodeBody(codecId, body))
      .build();
  }

  /** 构建 JSON 格式的消息体 ObjectNode */
  protected ObjectNode jsonBody() {
    return MAPPER.createObjectNode();
  }
}
