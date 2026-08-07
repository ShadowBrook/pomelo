package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.codec.ProtobufCodec;
import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.config.ConfigHolder;
import com.github.moxib.pomelo.logic.infrastructure.MessageRepository;
import com.github.moxib.pomelo.logic.model.MessageRecord;
import com.github.moxib.pomelo.logic.model.requests.PullRequest;
import com.github.moxib.pomelo.proto.common.CommonProto;
import com.github.moxib.pomelo.proto.pull.PullProto;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

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
      // PB 的 PullReq 无 peerId 字段：proto 客户端通过 varHeaders["peerId"] 传会话历史目标
      String headerPeerId = message.getVarHeaders() != null ? message.getVarHeaders().get("peerId") : null;
      String peerId = req.peerId() != null && !req.peerId().isEmpty() && !"0".equals(req.peerId())
        ? req.peerId()
        : (headerPeerId != null && !headerPeerId.isEmpty() && !"0".equals(headerPeerId) ? headerPeerId : null);
      // lastMsgId 是通用游标：离线拉 = 收件人同步水位(sinceSeq)；会话历史 = 时间游标(beforeTime)
      long cursor = req.seq();
      int limit = req.limit() > 0 ? req.limit() : defaultPullLimit;

      if (userId == null || userId.isEmpty()) {
        return Future.succeededFuture(buildErrorResp(message, CMD_PULL_RESP_VALUE, ErrorCode.UNAUTHORIZED, "未认证用户"));
      }

      final boolean isHistoryPull = peerId != null;

      return resolveId(userId, peerId)
        .compose(resolved -> {
          if (isHistoryPull) {
            LOG.info("拉取会话历史: userId={}({}) peerId={}({}) beforeTime={} limit={}",
              userId, resolved.userId, peerId, resolved.peerId, cursor, limit);
            String conversationId = MessageServiceImpl.buildConversationId(resolved.userId, resolved.peerId);
            return messageRepo.pullConversation(conversationId, cursor, limit)
                .compose(records -> sendPullResp(message, records, limit));
          } else {
            LOG.info("拉取离线消息: userId={}({}) sinceSeq={} limit={}",
              userId, resolved.userId, cursor, limit);
            return messageRepo.pullPending(resolved.userId, cursor, limit)
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
    if (records == null || records.isEmpty()) {
      return Future.succeededFuture(buildPullResp(request, null, limit, java.util.Collections.emptyMap()));
    }
    // 收集所有待解析的 numeric id → 批量查 DB 获取 userId + userName + nickname
    java.util.Set<Long> numericIds = new java.util.HashSet<>();
    for (MessageRecord r : records) {
      numericIds.add(r.getSenderId());
      numericIds.add(r.getRecipientId());
    }
    return messageRepo.findUserIdsByIds(new java.util.ArrayList<>(numericIds))
      .compose(idToInfo -> Future.succeededFuture(buildPullResp(request, records, limit, idToInfo)))
      .recover(err -> {
        LOG.warn("批量查用户信息失败: {}", err.getMessage());
        return Future.succeededFuture(buildPullResp(request, records, limit, java.util.Collections.emptyMap()));
      });
  }

  private ImMessage buildPullResp(ImMessage request, List<MessageRecord> records, int limit,
                                   java.util.Map<Long, com.github.moxib.pomelo.logic.model.UserIdInfo> idToInfo) {
    byte codecId = request.getCodecId();
    Object respBody;

    if (codecId == ProtobufCodec.CODEC_ID) {
      PullProto.PullResp.Builder resp = PullProto.PullResp.newBuilder()
        .setCode(0).setMessage("success")
        .setHasMore(records != null && records.size() >= limit);
      // PB 通道也返回消息列表：MessageContent + ext{id, senderId, recipientId, seq, 显示名}
      if (records != null) {
        for (MessageRecord r : records) {
          com.github.moxib.pomelo.logic.model.UserIdInfo senderInfo = idToInfo.get(r.getSenderId());
          com.github.moxib.pomelo.logic.model.UserIdInfo recipientInfo = idToInfo.get(r.getRecipientId());
          CommonProto.MessageContent.Builder mc = CommonProto.MessageContent.newBuilder()
            .setMsgTypeValue(r.getMsgType())
            .setContent(com.google.protobuf.ByteString.copyFromUtf8(r.getContent() != null ? r.getContent() : ""))
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
          // 使用 String 避免 JavaScript Number 精度丢失（snowflake ID > 2^53）
          msg.put("id", String.valueOf(r.getId()));
          // 用 DB 查到的 NanoID + 显示名，fallback 到数字 ID
          com.github.moxib.pomelo.logic.model.UserIdInfo senderInfo = idToInfo.get(r.getSenderId());
          com.github.moxib.pomelo.logic.model.UserIdInfo recipientInfo = idToInfo.get(r.getRecipientId());
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
