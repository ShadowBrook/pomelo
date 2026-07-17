package com.github.moxib.pomelo.gateway.handler;

import com.github.moxib.pomelo.codec.CodecRegistry;
import com.github.moxib.pomelo.codec.ProtobufCodec;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.proto.ack.AckProto;
import com.github.moxib.pomelo.proto.common.CommonProto;
import com.github.moxib.pomelo.service.MessageRepository;
import com.github.moxib.pomelo.service.MessageService;
import com.github.moxib.pomelo.service.model.AckNotifyContext;
import com.github.moxib.pomelo.utils.IdGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.CMD_ACK_RESP_VALUE;
import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.CMD_ACK_NOTIFY_VALUE;

/**
 * ACK 消息确认处理器 — 支持双协议。
 */
public class AckReqHandler extends AbstractMessageHandler {

  private static final Logger LOG = LoggerFactory.getLogger(AckReqHandler.class);

  public AckReqHandler(Vertx vertx, CodecRegistry codecRegistry,
                        SessionRegistry sessionRegistry, MessageRepository messageRepo,
                        MessageService messageService, IdGenerator idGenerator) {
    super(vertx, codecRegistry, sessionRegistry, messageRepo, messageService, idGenerator);
  }

  @Override
  public void handle(Connection connection, ImMessage message) {
    try {
      byte codecId = message.getCodecId();
      List<Long> messageIds = new ArrayList<>();
      int ackType;

      if (codecId == ProtobufCodec.CODEC_ID) {
        AckProto.AckReq req = decodeBody(message);
        for (int i = 0; i < req.getMessageIdsCount(); i++) {
          messageIds.add((long) req.getMessageIds(i));
        }
        ackType = req.getAckTypeValue();
      } else {
        JsonNode json = parseJsonBody(message);
        if (json.has("messageIds")) {
          for (JsonNode idNode : json.get("messageIds")) {
            messageIds.add(idNode.asLong());
          }
        }
        ackType = json.has("ackType") ? json.get("ackType").asInt() : 0;
      }

      LOG.info("ACK: {} msgs type={} codec={}",
        messageIds.size(),
        ackType == CommonProto.AckType.RECEIVED_VALUE ? "RECEIVED" : "SEEN",
        codecId == 0 ? "PB" : "JSON");

      Map<String, String> headers = message.getVarHeaders();
      String ackFromUserId = (headers != null) ? headers.get("userId") : null;

      messageService.processAck(messageIds, ackType, ackFromUserId)
        .onSuccess(notifyContexts -> {
          // 推送 AckNotify 给每个原始发送者（用发送者的 codec）
          for (AckNotifyContext ctx : notifyContexts) {
            pushAckNotify(ctx);
          }

          // 回 AckResp 给确认者
          Object respBody;
          if (codecId == ProtobufCodec.CODEC_ID) {
            respBody = AckProto.AckResp.newBuilder().setAckTypeValue(ackType).build();
          } else {
            ObjectNode json = jsonBody();
            json.put("ackType", ackType);
            respBody = json;
          }

          ImMessage response = buildResponse(message, CMD_ACK_RESP_VALUE, respBody);
          sendResponse(connection, response);
        })
        .onFailure(e -> {
          LOG.error("ACK 处理失败", e);
          sendErrorResponse(connection, message, 500, "ACK 处理失败：" + e.getMessage());
        });

    } catch (Exception e) {
      LOG.error("ACK 请求解码失败", e);
      sendErrorResponse(connection, message, 400, "ACK 格式错误：" + e.getMessage());
    }
  }

  /** 推送 AckNotify 给原始发送者，使用发送者的 codec */
  private void pushAckNotify(AckNotifyContext ctx) {
    Connection senderConn = sessionRegistry.getConnection(ctx.getSenderId());
    if (senderConn == null) {
      LOG.debug("发送者 {} 离线，跳过 AckNotify", ctx.getSenderId());
      return;
    }

    try {
      Object body;
      if (sessionRegistry.getCodec(ctx.getSenderId()) == ProtobufCodec.CODEC_ID) {
        AckProto.AckNotify.Builder builder = AckProto.AckNotify.newBuilder()
          .setAckTypeValue(ctx.getAckType());
        for (Long id : ctx.getMessageIds()) {
          builder.addMessageIds(id.intValue());
        }
        body = builder.build();
      } else {
        ObjectNode json = jsonBody();
        json.put("ackType", ctx.getAckType());
        ArrayNode ids = json.putArray("messageIds");
        for (Long id : ctx.getMessageIds()) {
          ids.add(id);
        }
        body = json;
      }

      ImMessage imMsg = buildPushMessage(ctx.getSenderId(), CMD_ACK_NOTIFY_VALUE,
        String.valueOf(System.currentTimeMillis()), body);
      senderConn.write(imMsg.encodeToWire());
      LOG.debug("AckNotify 已推送: sender={} type={} count={}",
        ctx.getSenderId(), ctx.getAckType(), ctx.getMessageIds().size());
    } catch (Exception e) {
      LOG.error("推送 AckNotify 失败: {}", e.getMessage());
    }
  }
}
