package com.github.moxib.pomelo.gateway.handler;

import com.github.moxib.pomelo.codec.CodecRegistry;
import com.github.moxib.pomelo.codec.ProtobufCodec;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.proto.chat.ChatProto;
import com.github.moxib.pomelo.service.MessageRepository;
import com.github.moxib.pomelo.service.MessageService;
import com.github.moxib.pomelo.service.model.C2CReqContext;
import com.github.moxib.pomelo.utils.IdGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.CMD_C2C_RESP_VALUE;

/**
 * 单聊消息处理器 — 支持 Protobuf + JSON 双协议。
 */
public class C2CMessageHandler extends AbstractMessageHandler {

  private static final Logger LOG = LoggerFactory.getLogger(C2CMessageHandler.class);

  public C2CMessageHandler(Vertx vertx, CodecRegistry codecRegistry,
                            SessionRegistry sessionRegistry, MessageRepository messageRepo,
                            MessageService messageService, IdGenerator idGenerator) {
    super(vertx, codecRegistry, sessionRegistry, messageRepo, messageService, idGenerator);
  }

  @Override
  public void handle(Connection connection, ImMessage message) {
    try {
      byte codecId = message.getCodecId();
      String senderId;
      String recipientId;
      String content;
      int msgType;
      long timestamp;
      long clientMsgId;

      if (codecId == ProtobufCodec.CODEC_ID) {
        ChatProto.C2CReq req = decodeBody(message);
        recipientId = req.getRecipientId();
        senderId = extractSenderId(message, req.getSenderId());
        content = req.getMessage().getContent().toStringUtf8();
        msgType = req.getMessage().getMsgTypeValue();
        timestamp = req.getMessage().getTimestamp();
      } else {
        JsonNode json = parseJsonBody(message);
        recipientId = json.has("recipientId") ? json.get("recipientId").asText() : null;
        String bodySenderId = json.has("senderId") ? json.get("senderId").asText() : null;
        senderId = extractSenderId(message, bodySenderId);
        // JS SDK 发送的 JSON 结构为 {senderId, recipientId, message: {msgType, content}}
        JsonNode msgNode = json.has("message") ? json.get("message") : json;
        content = msgNode.has("content") ? msgNode.get("content").asText() : "";
        msgType = msgNode.has("msgType") ? msgNode.get("msgType").asInt() : 1;
        timestamp = msgNode.has("timestamp") ? msgNode.get("timestamp").asLong() : 0;
      }

      if (senderId == null || senderId.isEmpty() || recipientId == null || recipientId.isEmpty()) {
        sendErrorResponse(connection, message, 400, "senderId 和 recipientId 不能为空");
        return;
      }

      if (timestamp == 0) timestamp = System.currentTimeMillis();

      long parsedMsgId;
      try {
        parsedMsgId = Long.parseLong(message.getMessageId());
      } catch (NumberFormatException e) {
        parsedMsgId = System.currentTimeMillis();
      }
      clientMsgId = parsedMsgId;

      C2CReqContext ctx = C2CReqContext.builder()
        .messageId(clientMsgId)
        .senderId(senderId)
        .recipientId(recipientId)
        .msgType(msgType)
        .content(content)
        .timestamp(timestamp)
        .build();

      LOG.info("C2C 消息: sender={} recipient={} msgType={} codec={}",
        senderId, recipientId, msgType, codecId == 0 ? "PB" : "JSON");

      messageService.sendC2CMessage(ctx)
        .onSuccess(result -> {
          Object respBody;
          if (codecId == ProtobufCodec.CODEC_ID) {
            respBody = ChatProto.C2CResp.newBuilder()
              .setCode(result.getCode())
              .setMessage(result.getMessage())
              .setMessageId((int) result.getMessageId())
              .setServerTime(result.getServerTime())
              .setSeq(result.getSeq())
              .build();
          } else {
            ObjectNode json = jsonBody();
            json.put("code", result.getCode());
            json.put("message", result.getMessage());
            json.put("messageId", result.getMessageId());
            json.put("serverTime", result.getServerTime());
            json.put("seq", result.getSeq());
            respBody = json;
          }

          ImMessage response = buildResponse(message, CMD_C2C_RESP_VALUE, respBody);
          sendResponse(connection, response);
          LOG.debug("C2CResp 已返回: msgId={} seq={}", clientMsgId, result.getSeq());
        })
        .onFailure(e -> {
          LOG.error("C2C 消息处理失败", e);
          sendErrorResponse(connection, message, 500, "发送失败：" + e.getMessage());
        });

    } catch (Exception e) {
      LOG.error("C2C 消息解码失败", e);
      sendErrorResponse(connection, message, 400, "消息格式错误：" + e.getMessage());
    }
  }

  /** 从 varHeaders 取 senderId（防篡改），fallback 到 body 中的值 */
  private String extractSenderId(ImMessage message, String bodySenderId) {
    Map<String, String> headers = message.getVarHeaders();
    String hdrUserId = (headers != null) ? headers.get("userId") : null;
    return (hdrUserId != null) ? hdrUserId : bodySenderId;
  }
}
