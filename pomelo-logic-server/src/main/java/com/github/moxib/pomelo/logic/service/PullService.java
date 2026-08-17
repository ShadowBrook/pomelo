package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.codec.ProtobufCodec;
import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.config.ConfigHolder;
import com.github.moxib.pomelo.logic.infrastructure.MessageRepository;
import com.github.moxib.pomelo.logic.model.MessageRecord;
import com.github.moxib.pomelo.logic.model.UserIdInfo;
import com.github.moxib.pomelo.logic.model.requests.PullRequest;
import com.github.moxib.pomelo.proto.common.CommonProto;
import com.github.moxib.pomelo.proto.pull.PullProto;
import com.google.protobuf.ByteString;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.*;

public class PullService extends ServiceBase {

  private static final Logger LOG = LoggerFactory.getLogger(PullService.class);

  private final MessageRepository messageRepo;
  private final int defaultPullLimit;

  public PullService(MessageRepository messageRepo) {
    this.messageRepo = messageRepo;
    this.defaultPullLimit = ConfigHolder.getInt("message.pullLimit", 50);
  }

  public Future<ImMessage> process(ImMessage message) {
    try {
      PullRequest req = decode(message, PullRequest.class);
      String userId = req.userId() != null && !req.userId().isEmpty()
        ? req.userId() : getUserIdFromHeaders(message);
      String headerPeerId = message.getVarHeaders() != null ? message.getVarHeaders().get("peerId") : null;
      String peerId = req.peerId() != null && !req.peerId().isEmpty() && !"0".equals(req.peerId())
        ? req.peerId()
        : (headerPeerId != null && !headerPeerId.isEmpty() && !"0".equals(headerPeerId) ? headerPeerId : null);
      long cursor = req.seq();
      int limit = req.limit() > 0 ? req.limit() : defaultPullLimit;

      if (userId == null || userId.isEmpty()) {
        return Future.succeededFuture(buildErrorResp(message, CMD_PULL_RESP_VALUE, ErrorCode.UNAUTHORIZED, "未认证用户"));
      }

      final boolean isHistoryPull = peerId != null;

      long resolvedUserId = Long.parseLong(userId);
      long resolvedPeerId = peerId != null ? Long.parseLong(peerId) : 0;

      if (isHistoryPull) {
        LOG.info("拉取会话历史: userId={}({}) peerId={}({}) beforeTime={} limit={}",
          userId, resolvedUserId, peerId, resolvedPeerId, cursor, limit);
        String conversationId = MessageServiceImpl.buildConversationId(resolvedUserId, resolvedPeerId);
        return messageRepo.pullConversation(conversationId, cursor, limit)
            .compose(records -> sendPullResp(message, records, limit));
      } else {
        LOG.info("拉取离线消息: userId={}({}) sinceSeq={} limit={}",
          userId, resolvedUserId, cursor, limit);
        return messageRepo.pullPending(resolvedUserId, cursor, limit)
          .compose(records -> sendPullResp(message, records, limit));
      }
    } catch (Exception e) {
      LOG.error("处理拉取请求失败", e);
      return Future.succeededFuture(buildErrorResp(message, CMD_PULL_RESP_VALUE, ErrorCode.INTERNAL_ERROR, "处理失败：" + e.getMessage()));
    }
  }

  private Future<ImMessage> sendPullResp(ImMessage request, List<MessageRecord> records, int limit) {
    if (records == null || records.isEmpty()) {
      return Future.succeededFuture(buildPullResp(request, null, limit, Collections.emptyMap()));
    }
    Set<Long> numericIds = new HashSet<>();
    for (MessageRecord r : records) {
      numericIds.add(r.getSenderId());
      numericIds.add(r.getRecipientId());
    }
    return messageRepo.findUserIdsByIds(new ArrayList<>(numericIds))
      .compose(idToInfo -> Future.succeededFuture(buildPullResp(request, records, limit, idToInfo)))
      .recover(err -> {
        LOG.warn("批量查用户信息失败: {}", err.getMessage());
        return Future.succeededFuture(buildPullResp(request, records, limit, Collections.emptyMap()));
      });
  }

  private ImMessage buildPullResp(ImMessage request, List<MessageRecord> records, int limit,
                                   Map<Long, UserIdInfo> idToInfo) {
    byte codecId = request.getCodecId();
    Object respBody;

    if (codecId == ProtobufCodec.CODEC_ID) {
      PullProto.PullResp.Builder resp = PullProto.PullResp.newBuilder()
        .setCode(0).setMessage("success")
        .setHasMore(records != null && records.size() >= limit);
      if (records != null) {
        for (MessageRecord r : records) {
          UserIdInfo senderInfo = idToInfo.get(r.getSenderId());
          UserIdInfo recipientInfo = idToInfo.get(r.getRecipientId());
          CommonProto.MessageContent.Builder mc = CommonProto.MessageContent.newBuilder()
            .setMsgTypeValue(r.getMsgType())
            .setContent(ByteString.copyFromUtf8(r.getContent() != null ? r.getContent() : ""))
            .setTimestamp(r.getCreatedAt());
          mc.putExt("id", String.valueOf(r.getId()));
          mc.putExt("senderId", senderInfo != null ? senderInfo.userId() : String.valueOf(r.getSenderId()));
          mc.putExt("recipientId", recipientInfo != null ? recipientInfo.userId() : String.valueOf(r.getRecipientId()));
          if (senderInfo != null && senderInfo.userName() != null) {
            mc.putExt("senderUserName", senderInfo.userName());
          }
          if (senderInfo != null && senderInfo.nickname() != null) {
            mc.putExt("senderNickname", senderInfo.nickname());
          }
          mc.putExt("seq", String.valueOf(r.getSeq()));
          resp.addMessages(mc);
        }
      }
      respBody = resp.build();
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
          msg.put("id", String.valueOf(r.getId()));
          UserIdInfo senderInfo = idToInfo.get(r.getSenderId());
          UserIdInfo recipientInfo = idToInfo.get(r.getRecipientId());
          msg.put("senderId", senderInfo != null ? senderInfo.userId() : String.valueOf(r.getSenderId()));
          msg.put("recipientId", recipientInfo != null ? recipientInfo.userId() : String.valueOf(r.getRecipientId()));
          if (senderInfo != null && senderInfo.userName() != null) {
            msg.put("senderUserName", senderInfo.userName());
          }
          if (senderInfo != null && senderInfo.nickname() != null) {
            msg.put("senderNickname", senderInfo.nickname());
          }
          msg.put("msgType", r.getMsgType());
          msg.put("content", r.getContent() != null ? r.getContent() : "");
          msg.put("seq", r.getSeq());
          msg.put("createdAt", r.getCreatedAt());
        }
      }
      respBody = json;
    }

    return buildResponse(request, CMD_PULL_RESP_VALUE, respBody);
  }
}
