package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.logic.infrastructure.MessageRepository;
import com.github.moxib.pomelo.logic.model.AckNotifyContext;
import com.github.moxib.pomelo.logic.model.C2CReqContext;
import com.github.moxib.pomelo.logic.model.C2CRespResult;
import com.github.moxib.pomelo.logic.model.MessageRecord;
import com.github.moxib.pomelo.seqsvr.client.SeqClientService;

import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.moxib.pomelo.proto.common.CommonProto.AckType;

/**
 * 消息业务逻辑实现（过渡版本 — SessionRegistry 依赖已移除，推送将在 Task 6 通过 EventBus publish 实现）。
 * 内部所有操作使用 im_user.id (BIGINT)，仅在推送协议层转换为 userId (NanoID)。
 */
public class MessageServiceImpl implements MessageService {

  private static final Logger LOG = LoggerFactory.getLogger(MessageServiceImpl.class);

  private final MessageRepository messageRepo;
  private final SeqClientService seqClient;

  public MessageServiceImpl(MessageRepository messageRepo, SeqClientService seqClient) {
    this.messageRepo = messageRepo;
    this.seqClient = seqClient;
  }

  @Override
  public Future<C2CRespResult> sendC2CMessage(C2CReqContext ctx) {
    Future<C2CRespResult> result = seqClient.fetchNextSequence(ctx.getSenderId())
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
              // Phase 2: 通过 EventBus publish 推送
              LOG.debug("C2C 消息已持久化: id={} seq={}, 推送将通过 EventBus 实现", record.getId(), record.getSeq());
            }
            return C2CRespResult.builder()
              .code(0)
              .message("success")
              .messageId(ctx.getMessageId())
              .seq(seq)
              .serverTime(now)
              .build();
          });
      });
    result.onFailure(e -> LOG.error("sendC2CMessage 失败: {}", e.getMessage()));
    return result;
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
              // senderUserId 将通过 EventBus 推送时解析
              results.add(new AckNotifyContext(senderId, null, entry.getValue(), ackType));
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
  public Future<List<MessageRecord>> pullConversationHistory(long userId, long peerId, long beforeTime, int limit) {
    String conversationId = buildConversationId(userId, peerId);
    return messageRepo.pullConversation(conversationId, beforeTime, limit)
      .onSuccess(list -> LOG.info("pullConversationHistory: conv={} beforeTime={} count={}",
        conversationId, beforeTime, list.size()))
      .onFailure(e -> LOG.error("pullConversationHistory 失败: {}", e.getMessage()));
  }

  /** 计算会话 ID — min(id1, id2):max(id1, id2) */
  public static String buildConversationId(long id1, long id2) {
    return id1 < id2 ? id1 + ":" + id2 : id2 + ":" + id1;
  }
}
