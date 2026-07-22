package com.github.moxib.pomelo.service;

import com.github.moxib.pomelo.codec.ProtobufCodec;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.gateway.handler.Connection;
import com.github.moxib.pomelo.gateway.handler.SessionRegistry;
import com.github.moxib.pomelo.proto.chat.ChatProto;
import com.github.moxib.pomelo.proto.common.CommonProto;
import com.github.moxib.pomelo.service.model.AckNotifyContext;
import com.github.moxib.pomelo.service.model.C2CReqContext;
import com.github.moxib.pomelo.service.model.C2CRespResult;
import com.github.moxib.pomelo.service.model.MessageRecord;
import com.github.moxib.pomelo.utils.IdGenerator;
import io.vertx.core.json.JsonObject;

import com.google.protobuf.ByteString;
import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.moxib.pomelo.common.ImMessage.MAGIC_NUMBER;
import static com.github.moxib.pomelo.common.ImMessage.WIRE_PROTOCOL_VERSION;
import static com.github.moxib.pomelo.proto.common.CommonProto.AckType;
import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.CMD_C2C_NOTIFY_VALUE;

/**
 * 消息业务逻辑实现。
 * 内部所有操作使用 im_user.id (BIGINT)，仅在推送协议层转换为 userId (NanoID)。
 */
public class MessageServiceImpl implements MessageService {

  private static final Logger LOG = LoggerFactory.getLogger(MessageServiceImpl.class);

  private final MessageRepository messageRepo;
  private final SessionRegistry sessionRegistry;
  private final IdGenerator idGenerator;

  public MessageServiceImpl(MessageRepository messageRepo, SessionRegistry sessionRegistry, IdGenerator idGenerator) {
    this.messageRepo = messageRepo;
    this.sessionRegistry = sessionRegistry;
    this.idGenerator = idGenerator;
  }

  @Override
  public Future<C2CRespResult> sendC2CMessage(C2CReqContext ctx) {
    return idGenerator.nextId()
      .compose(seq -> {
        long now = System.currentTimeMillis();
        String convId = buildConversationId(ctx.getSenderId(), ctx.getRecipientId());
        MessageRecord record = MessageRecord.builder()
          .id(ctx.getMessageId())
          .senderId(ctx.getSenderId())
          .recipientId(ctx.getRecipientId())
          .conversationId(convId)
          .msgType(ctx.getMsgType())
          .content(ctx.getContent())
          .seq(seq)
          .status(0)
          .createdAt(now)
          .build();

        return messageRepo.save(record)
          .map(inserted -> {
            if (inserted) {
              String senderUserId = ctx.getSenderUserId();
              String recipientUserId = sessionRegistry.getUserId(ctx.getRecipientId());
              pushToRecipient(record, senderUserId, recipientUserId, ctx.getSenderUserName(), ctx.getSenderNickname());
            }
            return C2CRespResult.builder()
              .code(0)
              .message("success")
              .messageId(ctx.getMessageId())
              .seq(seq)
              .serverTime(now)
              .build();
          });
      })
      .onFailure(e -> LOG.error("sendC2CMessage 失败: {}", e.getMessage()));
  }

  private void pushToRecipient(MessageRecord record, String senderUserId, String recipientUserId,
                                 String senderUserName, String senderNickname) {
    Connection recipientConn = sessionRegistry.getConnection(record.getRecipientId());
    if (recipientConn == null) {
      LOG.debug("接收方 id={} 离线，消息已存入 DB 待 Pull: msgId={}", record.getRecipientId(), record.getId());
      return;
    }

    // 如果 recipientUserId 未在线查到，fallback 用 id 转字符串
    String recipStr = recipientUserId != null ? recipientUserId : String.valueOf(record.getRecipientId());
    String senderStr = senderUserId != null ? senderUserId : String.valueOf(record.getSenderId());
    try {
      byte codecId = sessionRegistry.getCodec(record.getRecipientId());
      byte[] body;
      if (codecId == ProtobufCodec.CODEC_ID) {
        CommonProto.MessageContent msgContent = CommonProto.MessageContent.newBuilder()
          .setMsgTypeValue(record.getMsgType())
          .setContent(ByteString.copyFromUtf8(record.getContent() != null ? record.getContent() : ""))
          .build();
        ChatProto.C2CNotify notify = ChatProto.C2CNotify.newBuilder()
          .setSenderId(senderStr)
          .setRecipientId(recipStr)
          .setMessage(msgContent)
          .setSeq(record.getSeq())
          .build();
        body = notify.toByteArray();
      } else {
        JsonObject json = new JsonObject();
        json.put("senderId", senderStr);
        json.put("recipientId", recipStr);
        if (senderUserName != null) json.put("senderUserName", senderUserName);
        if (senderNickname != null) json.put("senderNickname", senderNickname);
        json.put("conversationId", record.getConversationId());
        json.put("seq", record.getSeq());
        JsonObject msgContent = new JsonObject(); json.put("message", msgContent);
        msgContent.put("msgType", record.getMsgType());
        msgContent.put("content", record.getContent() != null ? record.getContent() : "");
        json.put("id", record.getId());
        json.put("createdAt", record.getCreatedAt());
        body = json.toBuffer().getBytes();
      }

      ImMessage imMsg = ImMessage.builder()
        .magic(MAGIC_NUMBER)
        .version(WIRE_PROTOCOL_VERSION)
        .codecId(codecId)
        .cmd(CMD_C2C_NOTIFY_VALUE)
        .messageId(String.valueOf(record.getId()))
        .body(body)
        .build();

      recipientConn.write(imMsg.encodeToWire());
      LOG.debug("C2CNotify 已推送: recipientId={} codec={} msgId={} seq={}",
        record.getRecipientId(), codecId == 0 ? "PB" : "JSON", record.getId(), record.getSeq());
    } catch (Exception e) {
      LOG.error("推送 C2CNotify 失败: {}", e.getMessage());
    }
  }

  @Override
  public Future<List<AckNotifyContext>> processAck(List<Long> messageIds, int ackType,
                                                    long ackFromUserId, String ackFromUserIdStr) {
    if (messageIds == null || messageIds.isEmpty()) {
      return Future.succeededFuture(Collections.emptyList());
    }

    int newStatus = (ackType == AckType.RECEIVED_VALUE) ? 1 : 2;

    return messageRepo.findByIds(messageIds)
      .compose(records -> {
        if (records.isEmpty()) {
          LOG.warn("processAck: 未找到匹配消息 count={}", messageIds.size());
          return Future.succeededFuture(Collections.<AckNotifyContext>emptyList());
        }

        return messageRepo.batchUpdateStatus(messageIds, newStatus)
          .compose(v -> {
            Map<Long, List<Long>> senderMessages = new LinkedHashMap<>();
            for (MessageRecord r : records) {
              senderMessages.computeIfAbsent(r.getSenderId(), k -> new ArrayList<>()).add(r.getId());
            }

            List<AckNotifyContext> results = new ArrayList<>();
            for (Map.Entry<Long, List<Long>> entry : senderMessages.entrySet()) {
              long senderId = entry.getKey();
              String senderUserId = sessionRegistry.getUserId(senderId);
              results.add(new AckNotifyContext(senderId, senderUserId, entry.getValue(), ackType));
            }
            LOG.info("processAck: updated {} msgs to status={}, notify {} senders",
              messageIds.size(), newStatus, results.size());
            return Future.succeededFuture(results);
          });
      })
      .onFailure(e -> LOG.error("processAck 失败: {}", e.getMessage()));
  }

  @Override
  public Future<List<MessageRecord>> pullOfflineMessages(long userId, long sinceSeq, int limit) {
    return messageRepo.pullPending(userId, sinceSeq, limit)
      .onSuccess(list -> LOG.info("pullOfflineMessages: userId={} sinceSeq={} count={}",
        userId, sinceSeq, list.size()))
      .onFailure(e -> LOG.error("pullOfflineMessages 失败: {}", e.getMessage()));
  }

  @Override
  public Future<List<MessageRecord>> pullConversationHistory(long userId, long peerId, long beforeSeq, int limit) {
    String conversationId = buildConversationId(userId, peerId);
    return messageRepo.pullConversation(conversationId, beforeSeq, limit)
      .onSuccess(list -> LOG.info("pullConversationHistory: conv={} beforeSeq={} count={}",
        conversationId, beforeSeq, list.size()))
      .onFailure(e -> LOG.error("pullConversationHistory 失败: {}", e.getMessage()));
  }

  /** 计算会话 ID — min(id1, id2):max(id1, id2) */
  public static String buildConversationId(long id1, long id2) {
    return id1 < id2 ? id1 + ":" + id2 : id2 + ":" + id1;
  }
}
