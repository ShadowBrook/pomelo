package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.codec.ProtobufCodec;
import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.logic.codec.CodecRegistryHolder;
import com.github.moxib.pomelo.proto.common.CommonProto;
import io.vertx.core.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Service 基类 — 提供公共的编解码和响应构建方法。
 */
public abstract class ServiceBase {

  protected ImMessage buildResponse(ImMessage request, int cmd, Object body) {
    byte codecId = request.getCodecId();
    return ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId(codecId)
      .cmd(cmd)
      .messageId(request.getMessageId())
      .body(encodeBody(codecId, cmd, body))
      .varHeaders(new HashMap<>())
      .build();
  }

  protected ImMessage buildErrorResp(ImMessage request, int responseCmd, ErrorCode error, String detail) {
    String msg = detail != null ? detail : error.getDefaultMessage();
    byte codecId = request.getCodecId();
    Object body = codecId == ProtobufCodec.CODEC_ID
      ? CommonProto.ErrorBody.newBuilder().setCode(error.getCode()).setMessage(msg).build()
      : new JsonObject().put("code", error.getCode()).put("message", msg);
    return buildResponse(request, responseCmd, body);
  }

  protected byte[] encodeBody(byte codecId, int cmd, Object body) {
    var codec = CodecRegistryHolder.REGISTRY.getCodec(cmd, codecId);
    if (codec != null) {
      try { return codec.encode(body); }
      catch (UnsupportedOperationException ignored) {}
    }
    if (codecId == ProtobufCodec.CODEC_ID && body instanceof com.google.protobuf.Message msg) {
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

  protected String getUserIdFromHeaders(ImMessage message) {
    Map<String, String> headers = message.getVarHeaders();
    return headers != null ? headers.get("userId") : null;
  }

  protected String getBodyAsString(ImMessage message) {
    if (message.getBody() == null || message.getBody().length == 0) return null;
    return new String(message.getBody(), StandardCharsets.UTF_8);
  }

  protected JsonObject jsonBody() {
    return new JsonObject();
  }

  /**
   * 按 codec 分派双格式响应体：Protobuf 用 protobufSupplier，JSON 用 jsonSupplier。
   */
  protected Object dualBody(byte codecId, Supplier<Object> protobufSupplier, Supplier<Object> jsonSupplier) {
    return codecId == ProtobufCodec.CODEC_ID ? protobufSupplier.get() : jsonSupplier.get();
  }

  /**
   * 类型安全的消息解码 — 从全局 CodecRegistryHolder 中查找对应 codec 并解码到指定 DTO 类型。
   */
  @SuppressWarnings("unchecked")
  protected <T> T decode(ImMessage message, Class<T> type) {
    return (T) CodecRegistryHolder.REGISTRY.getCodec(message.getCmd(), message.getCodecId()).decode(message.getBody());
  }
}
