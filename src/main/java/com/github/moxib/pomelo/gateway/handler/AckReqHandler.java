package com.github.moxib.pomelo.gateway.handler;

import com.github.moxib.pomelo.codec.CodecRegistry;
import com.github.moxib.pomelo.codec.ProtobufCodec;
import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.proto.ack.AckProto;
import com.github.moxib.pomelo.proto.common.CommonProto;
import com.github.moxib.pomelo.service.MessageRepository;
import com.github.moxib.pomelo.service.MessageService;
import com.github.moxib.pomelo.service.model.AckNotifyContext;
import com.github.moxib.pomelo.service.model.requests.AckRequest;
import com.github.moxib.pomelo.utils.IdGenerator;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.CMD_ACK_RESP_VALUE;
import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.CMD_ACK_NOTIFY_VALUE;

/**
 * ACK 消息确认处理器。
 * 协议层使用 userId (NanoID)，内部解析为 im_user.id (BIGINT)。
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
      AckRequest req = decodeRequest(message, AckRequest.class);
      List<Long> messageIds = req.messageIds();
      int ackType = req.ackType();

      if (ackType != CommonProto.AckType.RECEIVED_VALUE && ackType != CommonProto.AckType.SEEN_VALUE) {
        sendErrorResponse(connection, message, CMD_ACK_RESP_VALUE, ErrorCode.BAD_REQUEST, "无效的 ackType: " + ackType);
        return;
      }

      boolean isSeen = ackType == CommonProto.AckType.SEEN_VALUE;
      LOG.info("ACK: {} msgs {} codec={}",
        messageIds.size(), isSeen ? "SEEN" : "RECEIVED", codecId == 0 ? "PB" : "JSON");

      String ackFromUserId = getUserIdFromHeaders(message);
      long ackFromId = sessionRegistry.getId(ackFromUserId);

      messageService.processAck(messageIds, ackType, ackFromId, ackFromUserId)
        .onSuccess(notifyContexts -> {
          for (AckNotifyContext ctx : notifyContexts) {
            pushAckNotify(ctx);
          }

          Object respBody;
          if (codecId == ProtobufCodec.CODEC_ID) {
            respBody = AckProto.AckResp.newBuilder().setAckTypeValue(ackType).build();
          } else {
            JsonObject json = jsonBody();
            json.put("ackType", ackType);
            respBody = json;
          }

          ImMessage response = buildResponse(message, CMD_ACK_RESP_VALUE, respBody);
          sendResponse(connection, response);
        })
        .onFailure(e -> {
          LOG.error("ACK 处理失败", e);
          sendErrorResponse(connection, message, CMD_ACK_RESP_VALUE, ErrorCode.INTERNAL_ERROR, "ACK 处理失败：" + e.getMessage());
        });

    } catch (Exception e) {
      LOG.error("ACK 请求解码失败", e);
      sendErrorResponse(connection, message, CMD_ACK_RESP_VALUE, ErrorCode.BAD_REQUEST, "ACK 格式错误：" + e.getMessage());
    }
  }

  private void pushAckNotify(AckNotifyContext ctx) {
    Connection senderConn = sessionRegistry.getConnection(ctx.getSenderId());
    if (senderConn == null) {
      LOG.debug("发送者 id={} 离线，跳过 AckNotify", ctx.getSenderId());
      return;
    }

    try {
      Object body;
      if (sessionRegistry.getCodec(ctx.getSenderId()) == ProtobufCodec.CODEC_ID) {
        AckProto.AckNotify.Builder builder = AckProto.AckNotify.newBuilder()
          .setAckTypeValue(ctx.getAckType());
        for (Long id : ctx.getMessageIds()) {
          builder.addMessageIds(id);
        }
        body = builder.build();
      } else {
        JsonObject json = jsonBody();
        json.put("ackType", ctx.getAckType());
        JsonArray ids = new JsonArray(); json.put("messageIds", ids);
        for (Long id : ctx.getMessageIds()) {
          ids.add(id);
        }
        body = json;
      }

      String targetUserId = ctx.getSenderUserId() != null ? ctx.getSenderUserId() : String.valueOf(ctx.getSenderId());
      ImMessage imMsg = buildPushMessage(targetUserId, CMD_ACK_NOTIFY_VALUE,
        String.valueOf(System.currentTimeMillis()), body);
      senderConn.write(imMsg.encodeToWire());
      LOG.debug("AckNotify 已推送: sender={} type={} count={}",
        ctx.getSenderId(), ctx.getAckType(), ctx.getMessageIds().size());
    } catch (Exception e) {
      LOG.error("推送 AckNotify 失败: {}", e.getMessage());
    }
  }
}
