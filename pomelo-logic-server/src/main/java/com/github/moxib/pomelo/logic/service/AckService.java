package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.logic.infrastructure.MessageRepository;
import com.github.moxib.pomelo.logic.model.AckNotifyContext;
import com.github.moxib.pomelo.logic.model.MessageRecord;
import com.github.moxib.pomelo.logic.model.requests.AckRequest;
import com.github.moxib.pomelo.model.PushEnvelope;
import com.github.moxib.pomelo.proto.ack.AckProto;
import com.github.moxib.pomelo.proto.common.CommonProto;
import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.*;

public class AckService extends ServiceBase {

  private static final Logger LOG = LoggerFactory.getLogger(AckService.class);

  private final PushRouter pushRouter;
  private final MessageRepository messageRepo;

  public AckService(PushRouter pushRouter, MessageRepository messageRepo) {
    this.pushRouter = pushRouter;
    this.messageRepo = messageRepo;
  }

  public Future<ImMessage> process(ImMessage message) {
    try {
      AckRequest req = decode(message, AckRequest.class);
      List<Long> messageIds = req.messageIds();
      int ackType = req.ackType();

      if (ackType != CommonProto.AckType.RECEIVED_VALUE && ackType != CommonProto.AckType.SEEN_VALUE) {
        return Future.succeededFuture(buildErrorResp(message, CMD_ACK_RESP_VALUE, ErrorCode.BAD_REQUEST, "无效的 ackType: " + ackType));
      }

      // 身份只信 gateway 规范化后的 varHeader
      String userId = getUserIdFromHeaders(message);
      if (userId == null || userId.isEmpty()) {
        return Future.succeededFuture(buildErrorResp(message, CMD_ACK_RESP_VALUE, ErrorCode.UNAUTHORIZED, "未认证用户"));
      }
      long ackerId = Long.parseLong(userId);

      boolean isSeen = ackType == CommonProto.AckType.SEEN_VALUE;
      LOG.info("ACK: {} msgs {}", messageIds.size(), isSeen ? "SEEN" : "RECEIVED");

      return processAckInternal(ackerId, messageIds, ackType)
        .map(notifyContexts -> {
          for (AckNotifyContext ctx : notifyContexts) {
            publishAckNotify(ctx);
          }
          AckProto.AckResp respBody = AckProto.AckResp.newBuilder().setAckTypeValue(ackType).build();
          return buildResponse(message, CMD_ACK_RESP_VALUE, respBody);
        });
    } catch (Exception e) {
      LOG.error("ACK 处理失败", e);
      return Future.succeededFuture(buildErrorResp(message, CMD_ACK_RESP_VALUE, ErrorCode.BAD_REQUEST, "ACK 格式错误: " + e.getMessage()));
    }
  }

  private Future<List<AckNotifyContext>> processAckInternal(long ackerId, List<Long> messageIds, int ackType) {
    if (messageIds == null || messageIds.isEmpty()) {
      return Future.succeededFuture(Collections.emptyList());
    }
    int newStatus = (ackType == CommonProto.AckType.RECEIVED_VALUE) ? 1 : 2;

    // 查询与更新都带 recipient_id 条件：只能 ACK 发给自己的消息
    return messageRepo.findByIds(ackerId, messageIds)
      .compose(records -> {
        if (records.isEmpty()) {
          LOG.warn("ACK: 未找到匹配消息 count={} acker={}", messageIds.size(), ackerId);
          return Future.succeededFuture(Collections.emptyList());
        }
        return messageRepo.batchUpdateStatus(ackerId, messageIds, newStatus)
          .map(v -> {
            Map<Long, List<Long>> senderMessages = new LinkedHashMap<>();
            for (MessageRecord r : records) {
              senderMessages.computeIfAbsent(r.getSenderId(), k -> new ArrayList<>()).add(r.getId());
            }
            // ACK_NOTIFY 按发送者会话路由
            List<AckNotifyContext> results = new ArrayList<>();
            for (Map.Entry<Long, List<Long>> entry : senderMessages.entrySet()) {
              long senderId = entry.getKey();
              results.add(new AckNotifyContext(senderId, String.valueOf(senderId), entry.getValue(), ackType));
            }
            LOG.info("ACK: updated {} msgs to status={}, notify {} senders", messageIds.size(), newStatus, results.size());
            return results;
          });
      });
  }

  private void publishAckNotify(AckNotifyContext ctx) {
    String targetUserId = ctx.getSenderUserId() != null ? ctx.getSenderUserId() : String.valueOf(ctx.getSenderId());
    AckProto.AckNotify.Builder builder = AckProto.AckNotify.newBuilder()
      .setAckTypeValue(ctx.getAckType());
    for (Long id : ctx.getMessageIds()) {
      builder.addMessageIds(id);
    }
    PushEnvelope env = new PushEnvelope(targetUserId, CMD_ACK_NOTIFY_VALUE, builder.build().toByteArray());
    pushRouter.push(env);
    LOG.debug("AckNotify 已发送: sender={} type={} count={}",
      ctx.getSenderId(), ctx.getAckType(), ctx.getMessageIds().size());
  }
}
