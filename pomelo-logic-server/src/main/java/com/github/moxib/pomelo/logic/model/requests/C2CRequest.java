package com.github.moxib.pomelo.logic.model.requests;

import com.github.moxib.pomelo.proto.chat.ChatProto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * C2C 单聊请求 DTO（PB C2CReq / JSON 共用）。
 * JSON 格式：{senderId, recipientId, message: {msgType, content}}，messageId/timestamp 来自 wire 协议。
 * PB 路径通过 fromProto 映射。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record C2CRequest(
  String senderId,
  String recipientId,
  MessageBody message,
  long messageId,
  long timestamp
) {
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record MessageBody(int msgType, String content) {}

  public static C2CRequest fromProto(ChatProto.C2CReq proto) {
    return new C2CRequest(
      proto.getSenderId(),
      proto.getRecipientId(),
      new MessageBody(proto.getMessage().getMsgTypeValue(), proto.getMessage().getContent().toStringUtf8()),
      proto.getMessageId(),
      proto.getMessage().getTimestamp());
  }
}
