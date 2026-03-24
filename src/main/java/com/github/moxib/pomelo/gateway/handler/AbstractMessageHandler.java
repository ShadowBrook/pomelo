package com.github.moxib.pomelo.gateway.handler;

import com.github.moxib.pomelo.common.ImMessage;
import com.google.protobuf.Message;
import io.vertx.core.Vertx;

/**
 * 消息处理器抽象基类
 * 提供通用的方法和工具
 */
public abstract class AbstractMessageHandler implements MessageHandler {

  protected final Vertx vertx;

  public AbstractMessageHandler(Vertx vertx) {
    this.vertx = vertx;
  }

  /**
   * 发送响应消息给客户端
   *
   * @param connection 客户端连接
   * @param response 响应消息
   */
  protected void sendResponse(Connection connection, ImMessage response) {
    if (response != null) {
      connection.write(response.encodeToWire());
    }
  }

  /**
   * 发送错误响应给客户端
   *
   * @param connection 客户端连接
   * @param request 请求消息
   * @param errorCode 错误码
   * @param errorMsg 错误信息
   */
  protected void sendErrorResponse(Connection connection, ImMessage request, int errorCode, String errorMsg) {
    ImMessage response = ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId(request.getCodecId())
      .cmd(errorCode)
      .messageId(request.getMessageId())
      .body(errorMsg.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      .build();
    sendResponse(connection, response);
  }

  /**
   * 获取请求消息体字符串
   *
   * @param message 请求消息
   * @return 消息体字符串
   */
  protected String getBodyAsString(ImMessage message) {
    if (message.getBody() == null || message.getBody().length == 0) {
      return null;
    }
    return new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
  }

  /**
   * 解码 Protobuf 消息
   *
   * @param data 原始字节数据
   * @param messageType 消息类型
   * @return 解码后的消息对象
   */
  @SuppressWarnings("unchecked")
  protected <T extends Message> T decodeProtobuf(byte[] data, Class<T> messageType) {
    try {
      // 使用 ProtobufCodec 的静态方法获取解码器
      // 这里直接使用 Message 的 parseFrom 方法
      java.lang.reflect.Method method = messageType.getMethod("parseFrom", byte[].class);
      return (T) method.invoke(null, data);
    } catch (Exception e) {
      throw new RuntimeException("Failed to decode protobuf message", e);
    }
  }

  /**
   * 编码 Protobuf 消息
   *
   * @param message Protobuf 消息对象
   * @return 编码后的字节数组
   */
  protected byte[] encodeProtobuf(Message message) {
    return message.toByteArray();
  }
}
