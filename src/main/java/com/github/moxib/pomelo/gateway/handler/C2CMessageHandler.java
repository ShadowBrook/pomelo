package com.github.moxib.pomelo.gateway.handler;

import com.github.moxib.pomelo.codec.CodecRegistry;
import com.github.moxib.pomelo.codec.ProtobufCodec;
import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.proto.chat.ChatProto;
import com.github.moxib.pomelo.service.MessageRepository;
import com.github.moxib.pomelo.service.MessageService;
import com.github.moxib.pomelo.service.model.C2CReqContext;
import com.github.moxib.pomelo.service.model.requests.C2CRequest;
import com.github.moxib.pomelo.utils.IdGenerator;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.CMD_C2C_RESP_VALUE;

/**
 * 单聊消息处理器 — 支持 Protobuf + JSON 双协议。
 * 协议层使用 userId (NanoID)，内部转换为 im_user.id (BIGINT)。
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
      C2CRequest req = decodeRequest(message, C2CRequest.class);
      C2CRequest.MessageBody msg = req.message();
      String senderUserId = extractSenderUserId(message, req.senderId());
      String recipientUserId = req.recipientId();
      String content = msg.content();
      int msgType = msg.msgType();
      // messageId/timestamp 优先从 body 取（PB 路径），fallback 到 wire 协议（JSON 路径）
      long clientMsgId = req.messageId() != 0 ? req.messageId() : parseWireMessageId(message);
      long timestamp = req.timestamp() != 0 ? req.timestamp() : System.currentTimeMillis();

      if (senderUserId == null || senderUserId.isEmpty() || recipientUserId == null || recipientUserId.isEmpty()) {
        sendErrorResponse(connection, message, CMD_C2C_RESP_VALUE, ErrorCode.BAD_REQUEST, "senderId 和 recipientId 不能为空");
        return;
      }

      // 解析 sender numeric id（发送者必须在线）
      long senderId = sessionRegistry.getId(senderUserId);
      if (senderId == 0) {
        sendErrorResponse(connection, message, CMD_C2C_RESP_VALUE, ErrorCode.UNAUTHORIZED, "发送者未登录");
        return;
      }

      final String fSenderUserId = senderUserId;
      final String fRecipientUserId = recipientUserId;
      final long fSenderId = senderId;
      final long fTimestamp = timestamp;
      final long fClientMsgId = clientMsgId;
      final int fMsgType = msgType;
      final String fContent = content;
      final byte fCodecId = codecId;

      LOG.info("C2C 消息: sender={}({}) recipient={} msgType={} codec={}",
        fSenderUserId, fSenderId, fRecipientUserId, fMsgType, fCodecId == 0 ? "PB" : "JSON");

      // 解析 recipient numeric id（在线直接取，离线查 DB）
      resolveId(fRecipientUserId)
        .onSuccess(recipientId -> {
          if (recipientId == 0) {
            sendErrorResponse(connection, message, CMD_C2C_RESP_VALUE, ErrorCode.NOT_FOUND, "接收者不存在");
            return;
          }

          C2CReqContext ctx = C2CReqContext.builder()
            .messageId(fClientMsgId)
            .senderId(fSenderId)
            .recipientId(recipientId)
            .senderUserId(fSenderUserId)
            .senderUserName(sessionRegistry.getUserName(fSenderId))
            .senderNickname(sessionRegistry.getNickname(fSenderId))
            .msgType(fMsgType)
            .content(fContent)
            .timestamp(fTimestamp)
            .build();

          messageService.sendC2CMessage(ctx)
            .onSuccess(result -> {
              Object respBody = fCodecId == ProtobufCodec.CODEC_ID
                ? ChatProto.C2CResp.newBuilder()
                    .setCode(result.getCode())
                    .setMessage(result.getMessage())
                    .setMessageId(result.getMessageId())
                    .setServerTime(result.getServerTime())
                    .setSeq(result.getSeq())
                    .build()
                : result;
              ImMessage response = buildResponse(message, CMD_C2C_RESP_VALUE, respBody);
              sendResponse(connection, response);
              LOG.debug("C2CResp 已返回: msgId={} seq={}", clientMsgId, result.getSeq());
            })
            .onFailure(e -> {
              LOG.error("C2C 消息处理失败", e);
              sendErrorResponse(connection, message, CMD_C2C_RESP_VALUE, ErrorCode.INTERNAL_ERROR, "发送失败：" + e.getMessage());
            });
        })
        .onFailure(e -> sendErrorResponse(connection, message, CMD_C2C_RESP_VALUE, ErrorCode.INTERNAL_ERROR, "解析接收者失败: " + e.getMessage()));

    } catch (Exception e) {
      LOG.error("C2C 消息解码失败", e);
      sendErrorResponse(connection, message, CMD_C2C_RESP_VALUE, ErrorCode.BAD_REQUEST, "消息格式错误：" + e.getMessage());
    }
  }

  /** userId→id 解析：先查 SessionRegistry，离线则查 DB */
  private Future<Long> resolveId(String userId) {
    long onlineId = sessionRegistry.getId(userId);
    if (onlineId != 0) return Future.succeededFuture(onlineId);
    return messageRepo.findUserId(userId);
  }

  /** 从 varHeaders 取 senderUserId（防篡改），fallback 到 body 中的值 */
  private String extractSenderUserId(ImMessage message, String bodySenderId) {
    String hdrUserId = getUserIdFromHeaders(message);
    return hdrUserId != null ? hdrUserId : bodySenderId;
  }

  /** 从 wire 协议 messageId 解析 client message id（JSON 路径 fallback） */
  private long parseWireMessageId(ImMessage message) {
    try { return Long.parseLong(message.getMessageId()); }
    catch (NumberFormatException e) { return System.currentTimeMillis(); }
  }
}
