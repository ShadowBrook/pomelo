package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.codec.ProtobufCodec;
import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.logic.codec.CodecRegistryHolder;
import com.github.moxib.pomelo.proto.common.CommonProto;

import java.util.HashMap;
import java.util.Map;

/**
 * Service 基类 — 提供公共的 Protobuf 编解码和响应构建方法。
 * 后端已切换到 Protobuf 单编解码，wire codecId 一律写 0。
 */
public abstract class ServiceBase {

  protected ImMessage buildResponse(ImMessage request, int cmd, Object body) {
    return ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId((byte) 0)
      .cmd(cmd)
      .messageId(request.getMessageId())
      .body(encodeBody(body))
      .varHeaders(new HashMap<>())
      .build();
  }

  protected ImMessage buildErrorResp(ImMessage request, int responseCmd, ErrorCode error, String detail) {
    String msg = detail != null ? detail : error.getDefaultMessage();
    Object body = CommonProto.ErrorBody.newBuilder().setCode(error.getCode()).setMessage(msg).build();
    return buildResponse(request, responseCmd, body);
  }

  protected byte[] encodeBody(Object body) {
    if (body instanceof com.google.protobuf.Message msg) {
      return msg.toByteArray();
    }
    throw new RuntimeException("Cannot encode non-protobuf body: " + (body != null ? body.getClass().getName() : "null"));
  }

  protected String getUserIdFromHeaders(ImMessage message) {
    Map<String, String> headers = message.getVarHeaders();
    return headers != null ? headers.get("userId") : null;
  }

  /**
   * 类型安全的消息解码 — Protobuf 单编解码。
   * 从全局 CodecRegistryHolder 查找 cmd 对应的 Protobuf codec，忽略 wire 上遗留的 codecId 头（冻结为 0）。
   */
  @SuppressWarnings("unchecked")
  protected <T> T decode(ImMessage message, Class<T> type) {
    return (T) CodecRegistryHolder.REGISTRY
      .getCodec(message.getCmd(), ProtobufCodec.CODEC_ID).decode(message.getBody());
  }
}
