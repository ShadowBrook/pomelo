package com.github.moxib.pomelo.gateway.handler;

import com.github.moxib.pomelo.codec.CodecRegistry;
import com.github.moxib.pomelo.codec.ProtobufCodec;
import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.config.ConfigHolder;
import com.github.moxib.pomelo.proto.pull.PullProto;
import com.github.moxib.pomelo.service.MessageRepository;
import com.github.moxib.pomelo.service.MessageService;
import com.github.moxib.pomelo.service.model.MessageRecord;
import com.github.moxib.pomelo.service.model.UserIdInfo;
import com.github.moxib.pomelo.service.model.requests.PullRequest;
import com.github.moxib.pomelo.utils.IdGenerator;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.CMD_PULL_RESP_VALUE;

/**
 * 消息拉取处理器。
 * 协议层使用 userId (NanoID)，内部解析为 im_user.id (BIGINT)。
 */
public class PullMessageHandler extends AbstractMessageHandler {

  private static final Logger LOG = LoggerFactory.getLogger(PullMessageHandler.class);

  private final int defaultPullLimit;

  public PullMessageHandler(Vertx vertx, CodecRegistry codecRegistry,
                             SessionRegistry sessionRegistry, MessageRepository messageRepo,
                             MessageService messageService, IdGenerator idGenerator) {
    super(vertx, codecRegistry, sessionRegistry, messageRepo, messageService, idGenerator);
    this.defaultPullLimit = ConfigHolder.getInt("message.pullLimit", 50);
  }

  @Override
  public void handle(Connection connection, ImMessage message) {
    try {
      PullRequest req = decodeRequest(message, PullRequest.class);
      // userId 优先从 body 取（JSON 路径），fallback 到 varHeaders（PB 路径）
      String userId = req.userId() != null && !req.userId().isEmpty()
        ? req.userId() : getUserIdFromHeaders(message);
      String peerId = req.peerId() != null && !req.peerId().isEmpty() ? req.peerId() : null;
      long sinceSeq = req.lastMsgId();
      int limit = req.limit() > 0 ? req.limit() : defaultPullLimit;

      if (userId == null || userId.isEmpty()) {
        sendErrorResponse(connection, message, CMD_PULL_RESP_VALUE, ErrorCode.UNAUTHORIZED, "未认证用户");
        return;
      }

      final boolean isHistoryPull = peerId != null && !peerId.isEmpty();
      final String fPeerId = peerId;
      final long fSinceSeq = sinceSeq;
      final int fLimit = limit;

      // resolve userId → id
      resolveId(userId, fPeerId)
        .onSuccess(resolved -> {
          if (isHistoryPull) {
            LOG.info("拉取会话历史: userId={}({}) peerId={}({}) beforeSeq={} limit={}",
              userId, resolved.userId, fPeerId, resolved.peerId, fSinceSeq, fLimit);
            messageService.pullConversationHistory(resolved.userId, resolved.peerId, fSinceSeq, fLimit)
              .compose(records -> sendPullResp(connection, message, records, fLimit))
              .onFailure(e -> {
                LOG.error("拉取会话历史失败", e);
                sendErrorResponse(connection, message, CMD_PULL_RESP_VALUE, ErrorCode.INTERNAL_ERROR, "拉取失败：" + e.getMessage());
              });
          } else {
            LOG.info("拉取离线消息: userId={}({}) sinceSeq={} limit={}",
              userId, resolved.userId, fSinceSeq, fLimit);
            messageService.pullOfflineMessages(resolved.userId, fSinceSeq, fLimit)
              .compose(records -> sendPullResp(connection, message, records, fLimit))
              .onFailure(e -> {
                LOG.error("拉取离线消息失败", e);
                sendErrorResponse(connection, message, CMD_PULL_RESP_VALUE, ErrorCode.INTERNAL_ERROR, "拉取失败：" + e.getMessage());
              });
          }
        })
        .onFailure(e -> sendErrorResponse(connection, message, CMD_PULL_RESP_VALUE, ErrorCode.INTERNAL_ERROR, "解析用户失败: " + e.getMessage()));

    } catch (Exception e) {
      LOG.error("处理拉取请求失败", e);
      sendErrorResponse(connection, message, CMD_PULL_RESP_VALUE, ErrorCode.INTERNAL_ERROR, "处理失败：" + e.getMessage());
    }
  }

  /** 同时解析 userId 和 peerId 到 numeric id */
  private record Resolved(long userId, long peerId) {}

  private Future<Resolved> resolveId(String userId, String peerId) {
    Future<Long> uidF = resolveUserId(userId);
    if (peerId == null) return uidF.map(uid -> new Resolved(uid, 0));
    return uidF.compose(uid ->
      resolveUserId(peerId).map(pid -> new Resolved(uid, pid)));
  }

  private Future<Long> resolveUserId(String userId) {
    long id = sessionRegistry.getId(userId);
    if (id != 0) return Future.succeededFuture(id);
    return messageRepo.findUserId(userId);
  }

  /**
   * 构建并发送 PULL_RESP。
   * 先将消息记录中的 numeric id 解析为 UserIdInfo（含 userId + 显示名）：
   * 优先查 SessionRegistry（在线用户），不在线的通过 DB 批量查询。
   */
  private Future<Void> sendPullResp(Connection connection, ImMessage request, List<MessageRecord> records, int limit) {
    if (records == null || records.isEmpty()) {
      doSendPullResp(connection, request, records, limit, Map.of());
      return Future.succeededFuture();
    }

    // 收集所有待解析的 numeric id
    Set<Long> numericIds = new HashSet<>();
    for (MessageRecord r : records) {
      numericIds.add(r.getSenderId());
      numericIds.add(r.getRecipientId());
    }

    // 优先从 SessionRegistry 解析（在线用户：含 userId + 显示名）
    Map<Long, UserIdInfo> idToInfo = new HashMap<>();
    List<Long> unresolved = new ArrayList<>();
    for (Long id : numericIds) {
      String userId = sessionRegistry.getUserId(id);
      if (userId != null) {
        String userName = sessionRegistry.getUserName(id);
        String nickname = sessionRegistry.getNickname(id);
        idToInfo.put(id, new UserIdInfo(userId, userName, nickname));
      } else {
        unresolved.add(id);
      }
    }

    // 剩余未解析的通过 DB 批量查询
    if (unresolved.isEmpty()) {
      doSendPullResp(connection, request, records, limit, idToInfo);
      return Future.succeededFuture();
    }

    return messageRepo.findUserIdsByIds(unresolved)
      .onSuccess(dbMap -> idToInfo.putAll(dbMap))
      .onFailure(e -> LOG.error("批量解析 userId 失败: {}", e.getMessage()))
      .map(v -> {
        doSendPullResp(connection, request, records, limit, idToInfo);
        return null;
      });
  }

  private void doSendPullResp(Connection connection, ImMessage request, List<MessageRecord> records, int limit,
                               Map<Long, UserIdInfo> idToInfo) {
    byte codecId = request.getCodecId();
    Object respBody;

    if (codecId == ProtobufCodec.CODEC_ID) {
      respBody = PullProto.PullResp.newBuilder()
        .setCode(0)
        .setMessage("success")
        .setHasMore(records != null && records.size() >= limit)
        .build();
    } else {
      JsonObject json = jsonBody();
      json.put("code", 0);
      json.put("message", "success");
      json.put("hasMore", records != null && records.size() >= limit);
      if (records != null && !records.isEmpty()) {
        JsonArray arr = new JsonArray(); json.put("messages", arr);
        for (MessageRecord r : records) {
          JsonObject msg = new JsonObject(); arr.add(msg);
          msg.put("id", r.getId());
          UserIdInfo senderInfo = idToInfo.get(r.getSenderId());
          UserIdInfo recipientInfo = idToInfo.get(r.getRecipientId());
          String senderUserId = senderInfo != null ? senderInfo.userId() : String.valueOf(r.getSenderId());
          String recipientUserId = recipientInfo != null ? recipientInfo.userId() : String.valueOf(r.getRecipientId());
          msg.put("senderId", senderUserId);
          msg.put("recipientId", recipientUserId);
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

    ImMessage response = buildResponse(request, CMD_PULL_RESP_VALUE, respBody);
    sendResponse(connection, response);
  }
}
