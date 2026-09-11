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
   * 收敛拉取条数：请求值非正时取服务端默认值，上界一律截到 max。
   * 请求值是客户端可控的，不设上界等于允许单请求把全量历史读进内存。
   */
  protected static int resolvePullLimit(int requested, int fallback, int max) {
    int limit = requested > 0 ? requested : fallback;
    return Math.min(limit, max);
  }

  /**
   * 解析幂等键来源：优先 proto body 的 messageId，其次 wire messageId（须为纯数字正数）。
   * 都拿不到有效值返回 0，由调用方用 Snowflake 兜底。
   * <p>
   * 这里绝不能回退到墙钟毫秒：同一发送者同一毫秒内的第二条消息会撞
   * {@code (sender_id, client_msg_id)} 唯一键，被 {@code ON CONFLICT DO NOTHING} 吞掉，
   * 而重试查回的是第一条消息，客户端收到"成功"确认且不再重发——消息静默丢失。
   */
  protected static long parseClientMsgId(long bodyMessageId, String wireMessageId) {
    if (bodyMessageId > 0) {
      return bodyMessageId;
    }
    if (wireMessageId == null) {
      return 0;
    }
    try {
      long parsed = Long.parseLong(wireMessageId.trim());
      return parsed > 0 ? parsed : 0;
    } catch (NumberFormatException e) {
      return 0;
    }
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
