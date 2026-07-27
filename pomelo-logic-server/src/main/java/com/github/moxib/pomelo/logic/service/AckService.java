package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.codec.CodecRegistry;
import com.github.moxib.pomelo.codec.ProtobufCodec;
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
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.*;

public class AckService extends ServiceBase {

  private static final Logger LOG = LoggerFactory.getLogger(AckService.class);

  private final Vertx vertx;
  private final MessageRepository messageRepo;
  private final CodecRegistry codecRegistry;

  public AckService(Vertx vertx, MessageRepository messageRepo) {
    this.vertx = vertx;
    this.messageRepo = messageRepo;
    this.codecRegistry = new CodecRegistry();
    codecRegistry.registerProtobuf(CMD_ACK_REQ_VALUE, AckProto.AckReq.parser(), AckRequest::fromProto, AckRequest.class);
    codecRegistry.registerJson(CMD_ACK_REQ_VALUE, AckRequest.class);
  }

  public Future<ImMessage> process(ImMessage message) {
    try {
      byte codecId = message.getCodecId();
      AckRequest req = decode(codecRegistry, message, AckRequest.class);
      List<Long> messageIds = req.messageIds();
      int ackType = req.ackType();

      if (ackType != CommonProto.AckType.RECEIVED_VALUE && ackType != CommonProto.AckType.SEEN_VALUE) {
        return Future.succeededFuture(buildErrorResp(message, CMD_ACK_RESP_VALUE, ErrorCode.BAD_REQUEST, "无效的 ackType: " + ackType));
      }

      boolean isSeen = ackType == CommonProto.AckType.SEEN_VALUE;
      LOG.info("ACK: {} msgs {} codec={}", messageIds.size(), isSeen ? "SEEN" : "RECEIVED", codecId == 0 ? "PB" : "JSON");

      return processAckInternal(messageIds, ackType)
        .map(notifyContexts -> {
          for (AckNotifyContext ctx : notifyContexts) {
            publishAckNotify(ctx);
          }
          Object respBody;
          if (codecId == ProtobufCodec.CODEC_ID) {
            respBody = AckProto.AckResp.newBuilder().setAckTypeValue(ackType).build();
          } else {
            respBody = new JsonObject().put("ackType", ackType);
          }
          return buildResponse(message, CMD_ACK_RESP_VALUE, respBody);
        });
    } catch (Exception e) {
      LOG.error("ACK 处理失败", e);
      return Future.succeededFuture(buildErrorResp(message, CMD_ACK_RESP_VALUE, ErrorCode.BAD_REQUEST, "ACK 格式错误: " + e.getMessage()));
    }
  }

  private Future<List<AckNotifyContext>> processAckInternal(List<Long> messageIds, int ackType) {
    if (messageIds == null || messageIds.isEmpty()) {
      return Future.succeededFuture(Collections.emptyList());
    }
    int newStatus = (ackType == CommonProto.AckType.RECEIVED_VALUE) ? 1 : 2;

    return messageRepo.findByIds(messageIds)
      .compose(records -> {
        if (records.isEmpty()) {
          LOG.warn("ACK: 未找到匹配消息 count={}", messageIds.size());
          return Future.succeededFuture(Collections.<AckNotifyContext>emptyList());
        }
        return messageRepo.batchUpdateStatus(messageIds, newStatus)
          .map(v -> {
            Map<Long, List<Long>> senderMessages = new LinkedHashMap<>();
            for (MessageRecord r : records) {
              senderMessages.computeIfAbsent(r.getSenderId(), k -> new ArrayList<>()).add(r.getId());
            }
            List<AckNotifyContext> results = new ArrayList<>();
            for (Map.Entry<Long, List<Long>> entry : senderMessages.entrySet()) {
              results.add(new AckNotifyContext(entry.getKey(), null, entry.getValue(), ackType));
            }
            LOG.info("ACK: updated {} msgs to status={}, notify {} senders", messageIds.size(), newStatus, results.size());
            return results;
          });
      });
  }

  private void publishAckNotify(AckNotifyContext ctx) {
    try {
      AckProto.AckNotify.Builder builder = AckProto.AckNotify.newBuilder()
        .setAckTypeValue(ctx.getAckType());
      for (Long id : ctx.getMessageIds()) {
        builder.addMessageIds(id);
      }
      PushEnvelope env = new PushEnvelope(
        String.valueOf(ctx.getSenderId()),
        CMD_ACK_NOTIFY_VALUE,
        builder.build().toByteArray(),
        (byte) 0
      );
      vertx.eventBus().publish("gateway.push", JsonObject.mapFrom(env));
      LOG.debug("AckNotify 已广播: sender={} type={} count={}", ctx.getSenderId(), ctx.getAckType(), ctx.getMessageIds().size());
    } catch (Exception e) {
      LOG.error("推送 AckNotify 失败: {}", e.getMessage());
    }
  }
}
