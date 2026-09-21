package com.github.moxib.pomelo.logic.model.requests;

import com.github.moxib.pomelo.proto.group.GroupProto;
import io.vertx.core.json.JsonObject;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * C2G 群消息发送请求 DTO（PB C2GReq / JSON 共用）。
 * JSON 格式：{groupId, message: {msgType, content}}；senderId 走 wire varHeader，messageId 走 wire 协议。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record C2GRequest(
  String groupId,
  MessageBody message,
  long messageId
) {
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record MessageBody(int msgType, String content, String ext) {}

  public static C2GRequest fromProto(GroupProto.C2GReq proto) {
    // ext 为客户端扩展元数据通道（如 @ 提及 mentioned_user_ids），原样转 JSON 入库
    String ext = null;
    if (proto.getMessage().getExtCount() > 0) {
      JsonObject o = new JsonObject();
      proto.getMessage().getExtMap().forEach(o::put);
      ext = o.encode();
    }
    return new C2GRequest(
      String.valueOf(proto.getGroupId()),
      new MessageBody(proto.getMessage().getMsgTypeValue(), proto.getMessage().getContent().toStringUtf8(), ext),
      proto.getMessageId());
  }
}
