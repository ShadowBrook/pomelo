package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.codec.CodecRegistry;
import com.github.moxib.pomelo.codec.ProtobufCodec;
import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.config.ConfigHolder;
import com.github.moxib.pomelo.logic.infrastructure.MessageRepository;
import com.github.moxib.pomelo.logic.model.MessageRecord;
import com.github.moxib.pomelo.logic.model.requests.PullRequest;
import com.github.moxib.pomelo.proto.pull.PullProto;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.*;

public class PullService extends ServiceBase {

  private static final Logger LOG = LoggerFactory.getLogger(PullService.class);

  private final Vertx vertx;
  private final MessageRepository messageRepo;
  private final CodecRegistry codecRegistry;
  private final int defaultPullLimit;

  public PullService(Vertx vertx, MessageRepository messageRepo) {
    this.vertx = vertx;
    this.messageRepo = messageRepo;
    this.codecRegistry = new CodecRegistry();
    codecRegistry.registerProtobuf(CMD_PULL_REQ_VALUE, PullProto.PullReq.parser(), PullRequest::fromProto, PullRequest.class);
    codecRegistry.registerJson(CMD_PULL_REQ_VALUE, PullRequest.class);
    this.defaultPullLimit = ConfigHolder.getInt("message.pullLimit", 50);
  }

  public Future<ImMessage> process(ImMessage message) {
    try {
      PullRequest req = decode(codecRegistry, message, PullRequest.class);
      String userId = req.userId() != null && !req.userId().isEmpty()
        ? req.userId() : getUserIdFromHeaders(message);
      String peerId = req.peerId() != null && !req.peerId().isEmpty() ? req.peerId() : null;
      long sinceSeq = req.lastMsgId();
      int limit = req.limit() > 0 ? req.limit() : defaultPullLimit;

      if (userId == null || userId.isEmpty()) {
        return Future.succeededFuture(buildErrorResp(message, CMD_PULL_RESP_VALUE, ErrorCode.UNAUTHORIZED, "未认证用户"));
      }

      final boolean isHistoryPull = peerId != null && !peerId.isEmpty();

      return resolveId(userId, peerId)
        .compose(resolved -> {
          if (isHistoryPull) {
            LOG.info("拉取会话历史: userId={}({}) peerId={}({}) beforeSeq={} limit={}",
              userId, resolved.userId, peerId, resolved.peerId, sinceSeq, limit);
            return MessageServiceImpl.buildConversationId(resolved.userId, resolved.peerId) != null
              ? messageRepo.pullConversation(MessageServiceImpl.buildConversationId(resolved.userId, resolved.peerId), sinceSeq, limit)
                  .compose(records -> sendPullResp(message, records, limit))
              : Future.succeededFuture();
          } else {
            LOG.info("拉取离线消息: userId={}({}) sinceSeq={} limit={}",
              userId, resolved.userId, sinceSeq, limit);
            return messageRepo.pullPending(resolved.userId, sinceSeq, limit)
              .compose(records -> sendPullResp(message, records, limit));
          }
        })
        .onFailure(e -> {
          LOG.error("拉取失败", e);
        });
    } catch (Exception e) {
      LOG.error("处理拉取请求失败", e);
      return Future.succeededFuture(buildErrorResp(message, CMD_PULL_RESP_VALUE, ErrorCode.INTERNAL_ERROR, "处理失败：" + e.getMessage()));
    }
  }

  private record Resolved(long userId, long peerId) {}

  private Future<Resolved> resolveId(String userId, String peerId) {
    Future<Long> uidF = resolveUserId(userId);
    if (peerId == null) return uidF.map(uid -> new Resolved(uid, 0));
    return uidF.compose(uid -> resolveUserId(peerId).map(pid -> new Resolved(uid, pid)));
  }

  private Future<Long> resolveUserId(String userId) {
    try { return Future.succeededFuture(Long.parseLong(userId)); }
    catch (NumberFormatException e) { return messageRepo.findUserId(userId); }
  }

  private Future<ImMessage> sendPullResp(ImMessage request, List<MessageRecord> records, int limit) {
    byte codecId = request.getCodecId();
    Object respBody;

    if (codecId == ProtobufCodec.CODEC_ID) {
      respBody = PullProto.PullResp.newBuilder()
        .setCode(0).setMessage("success")
        .setHasMore(records != null && records.size() >= limit)
        .build();
    } else {
      JsonObject json = jsonBody();
      json.put("code", 0).put("message", "success");
      json.put("hasMore", records != null && records.size() >= limit);
      if (records != null && !records.isEmpty()) {
        JsonArray arr = new JsonArray();
        json.put("messages", arr);
        for (MessageRecord r : records) {
          JsonObject msg = new JsonObject();
          arr.add(msg);
          msg.put("id", r.getId());
          msg.put("senderId", String.valueOf(r.getSenderId()));
          msg.put("recipientId", String.valueOf(r.getRecipientId()));
          msg.put("msgType", r.getMsgType());
          msg.put("content", r.getContent() != null ? r.getContent() : "");
          msg.put("seq", r.getSeq());
          msg.put("createdAt", r.getCreatedAt());
        }
      }
      respBody = json;
    }

    return Future.succeededFuture(buildResponse(request, CMD_PULL_RESP_VALUE, respBody));
  }
}
