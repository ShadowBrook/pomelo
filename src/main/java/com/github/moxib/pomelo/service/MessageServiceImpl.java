package com.github.moxib.pomelo.service;

import com.github.moxib.pomelo.codec.ProtobufCodec;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.gateway.handler.Connection;
import com.github.moxib.pomelo.gateway.handler.SessionRegistry;
import com.github.moxib.pomelo.proto.chat.ChatProto;
import com.github.moxib.pomelo.service.model.AckNotifyContext;
import com.github.moxib.pomelo.service.model.C2CReqContext;
import com.github.moxib.pomelo.service.model.C2CRespResult;
import com.github.moxib.pomelo.service.model.MessageRecord;
import com.github.moxib.pomelo.utils.IdGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * 推送消息时根据目标用户的 codec 自动选择 Protobuf 或 JSON 编码。
 */
public class MessageServiceImpl implements MessageService {

  private static final Logger LOG = LoggerFactory.getLogger(MessageServiceImpl.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

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
        MessageRecord record = MessageRecord.builder()
          .id(ctx.getMessageId())
          .senderId(ctx.getSenderId())
          .recipientId(ctx.getRecipientId())
          .msgType(ctx.getMsgType())
          .content(ctx.getContent())
          .seq(seq)
          .status(0)
          .createdAt(now)
          .build();

        return messageRepo.save(record)
          .map(inserted -> {
            // 仅首次成功插入时推送，幂等重复不推送
            if (inserted) {
              pushToRecipient(record);
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

  private void pushToRecipient(MessageRecord record) {
    Connection recipientConn = sessionRegistry.getConnection(record.getRecipientId());
    if (recipientConn == null) {
      LOG.debug("接收方 {} 离线，消息已存入 DB 待 Pull: msgId={}", record.getRecipientId(), record.getId());
      return;
    }

    try {
      byte codecId = sessionRegistry.getCodec(record.getRecipientId());
      byte[] body;
      if (codecId == ProtobufCodec.CODEC_ID) {
        ChatProto.C2CNotify notify = ChatProto.C2CNotify.newBuilder()
          .setSenderId(record.getSenderId())
          .setRecipientId(record.getRecipientId())
          .setSeq(record.getSeq())
          .build();
        body = notify.toByteArray();
      } else {
        ObjectNode json = MAPPER.createObjectNode();
        json.put("senderId", record.getSenderId());
        json.put("recipientId", record.getRecipientId());
        json.put("seq", record.getSeq());
        ObjectNode msgContent = json.putObject("message");
        msgContent.put("msgType", record.getMsgType());
        msgContent.put("content", record.getContent());
        json.put("id", record.getId());
        json.put("createdAt", record.getCreatedAt());
        body = MAPPER.writeValueAsBytes(json);
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
      LOG.debug("C2CNotify 已推送: userId={} codec={} msgId={} seq={}",
        record.getRecipientId(), codecId == 0 ? "PB" : "JSON", record.getId(), record.getSeq());
    } catch (Exception e) {
      LOG.error("推送 C2CNotify 失败: {}", e.getMessage());
    }
  }

  @Override
  public Future<List<AckNotifyContext>> processAck(List<Long> messageIds, int ackType, String ackFromUserId) {
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
            Map<String, List<Long>> senderMessages = new LinkedHashMap<>();
            for (MessageRecord r : records) {
              senderMessages.computeIfAbsent(r.getSenderId(), k -> new ArrayList<>()).add(r.getId());
            }

            List<AckNotifyContext> results = new ArrayList<>();
            for (Map.Entry<String, List<Long>> entry : senderMessages.entrySet()) {
              results.add(new AckNotifyContext(entry.getKey(), entry.getValue(), ackType));
            }
            LOG.info("processAck: updated {} msgs to status={}, notify {} senders",
              messageIds.size(), newStatus, results.size());
            return Future.succeededFuture(results);
          });
      })
      .onFailure(e -> LOG.error("processAck 失败: {}", e.getMessage()));
  }

  @Override
  public Future<List<MessageRecord>> pullOfflineMessages(String userId, long sinceSeq, int limit) {
    return messageRepo.pullPending(userId, sinceSeq, limit)
      .onSuccess(list -> LOG.info("pullOfflineMessages: userId={} sinceSeq={} count={}",
        userId, sinceSeq, list.size()))
      .onFailure(e -> LOG.error("pullOfflineMessages 失败: {}", e.getMessage()));
  }
}
