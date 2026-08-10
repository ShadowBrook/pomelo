package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.codec.ProtobufCodec;
import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.logic.infrastructure.GroupMsgWithSender;
import com.github.moxib.pomelo.logic.infrastructure.GroupRepository;
import com.github.moxib.pomelo.logic.infrastructure.MessageRepository;
import com.github.moxib.pomelo.logic.model.UserIdInfo;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.*;

public class GroupPullService extends ServiceBase {

  private static final Logger LOG = LoggerFactory.getLogger(GroupPullService.class);
  private static final int DEFAULT_LIMIT = 50;

  private final GroupRepository groupRepo;
  private final MessageRepository messageRepo;

  public GroupPullService(GroupRepository groupRepo, MessageRepository messageRepo) {
    this.groupRepo = groupRepo;
    this.messageRepo = messageRepo;
  }

  public Future<ImMessage> process(ImMessage message) {
    try {
      byte codecId = message.getCodecId();
      String userId = getUserIdFromHeaders(message);
      if (userId == null || userId.isEmpty()) {
        return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_PULL_MSG_RESP_VALUE,
          ErrorCode.UNAUTHORIZED, "未认证用户"));
      }

      String bodyStr = getBodyAsString(message);
      if (bodyStr == null) {
        return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_PULL_MSG_RESP_VALUE,
          ErrorCode.BAD_REQUEST, "body 不能为空"));
      }
      JsonObject body = new JsonObject(bodyStr);
      String groupId = body.getString("groupId");
      if (groupId == null || groupId.isEmpty()) {
        return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_PULL_MSG_RESP_VALUE,
          ErrorCode.BAD_REQUEST, "groupId 不能为空"));
      }
      long cursor = body.getLong("cursor", 0L);
      int limit = body.getInteger("limit", DEFAULT_LIMIT);
      boolean backward = body.getBoolean("isBackward", false);

      // backward=true 且 cursor<=0：从最新一页开始拉（seq < Long.MAX_VALUE），
      // 避免 seq < 0 永远查不到（群 seq 从 1 起）
      if (backward && cursor <= 0) {
        cursor = Long.MAX_VALUE;
      }

      LOG.info("拉取群消息: userId={} groupId={} cursor={} limit={} backward={}",
        userId, groupId, cursor, limit, backward);

      return groupRepo.pullMessages(groupId, cursor, limit, backward)
        .compose(msgs -> buildPullResp(message, codecId, msgs, limit));
    } catch (Exception e) {
      LOG.error("群消息拉取失败", e);
      return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_PULL_MSG_RESP_VALUE,
        ErrorCode.INTERNAL_ERROR, "拉取失败：" + e.getMessage()));
    }
  }

  private Future<ImMessage> buildPullResp(ImMessage request, byte codecId,
                                           List<GroupMsgWithSender> msgs, int limit) {
    if (msgs == null || msgs.isEmpty()) {
      return Future.succeededFuture(buildEmptyPullResp(request, codecId));
    }

    Set<Long> senderIds = msgs.stream()
      .map(GroupMsgWithSender::getSenderNumericId)
      .collect(Collectors.toSet());

    return messageRepo.findUserIdsByIds(new ArrayList<>(senderIds))
      .compose(idToInfo -> Future.succeededFuture(
        buildPullRespWithMessages(request, codecId, msgs, limit, idToInfo)))
      .recover(err -> Future.succeededFuture(
        buildPullRespWithMessages(request, codecId, msgs, limit, Collections.emptyMap())));
  }

  private ImMessage buildPullRespWithMessages(ImMessage request, byte codecId,
                                               List<GroupMsgWithSender> msgs, int limit,
                                               Map<Long, UserIdInfo> idToInfo) {
    Object respBody;
    if (codecId == ProtobufCodec.CODEC_ID) {
      PullProto.PullResp.Builder resp = PullProto.PullResp.newBuilder()
        .setCode(0).setMessage("success").setHasMore(msgs.size() >= limit);
      for (GroupMsgWithSender m : msgs) {
        UserIdInfo senderInfo = idToInfo.get(m.getSenderNumericId());
        CommonProto.MessageContent.Builder mc = CommonProto.MessageContent.newBuilder()
          .setMsgTypeValue(m.getMsgType())
          .setContent(ByteString.copyFromUtf8(m.getContent() != null ? m.getContent() : ""))
          .setTimestamp(m.getCreatedAt());
        mc.putExt("id", String.valueOf(m.getId()));
        mc.putExt("senderId", senderInfo != null ? senderInfo.userId() : String.valueOf(m.getSenderNumericId()));
        mc.putExt("groupId", m.getGroupId());
        if (senderInfo != null && senderInfo.userName() != null) {
          mc.putExt("senderUserName", senderInfo.userName());
        }
        if (senderInfo != null && senderInfo.nickname() != null) {
          mc.putExt("senderNickname", senderInfo.nickname());
        }
        mc.putExt("seq", String.valueOf(m.getSeq()));
        resp.addMessages(mc);
      }
      respBody = resp.build();
    } else {
      JsonObject json = jsonBody();
      json.put("code", 0).put("message", "success");
      json.put("hasMore", msgs.size() >= limit);
      JsonArray arr = new JsonArray();
      json.put("messages", arr);
      for (GroupMsgWithSender m : msgs) {
        JsonObject msg = new JsonObject();
        arr.add(msg);
        UserIdInfo senderInfo = idToInfo.get(m.getSenderNumericId());
        msg.put("id", String.valueOf(m.getId()));
        msg.put("senderId", senderInfo != null ? senderInfo.userId() : String.valueOf(m.getSenderNumericId()));
        msg.put("groupId", m.getGroupId());
        msg.put("msgType", m.getMsgType());
        msg.put("content", m.getContent() != null ? m.getContent() : "");
        msg.put("seq", m.getSeq());
        msg.put("createdAt", m.getCreatedAt());
        if (senderInfo != null) {
          if (senderInfo.userName() != null) msg.put("senderUserName", senderInfo.userName());
          if (senderInfo.nickname() != null) msg.put("senderNickname", senderInfo.nickname());
        }
      }
      respBody = json;
    }
    return buildResponse(request, CMD_GROUP_PULL_MSG_RESP_VALUE, respBody);
  }

  private ImMessage buildEmptyPullResp(ImMessage request, byte codecId) {
    Object respBody;
    // PbPullGroupMsgResp still works — regenerated proto only changed messages field type to MessageContent
    // Empty list is empty list regardless of element type
    if (codecId == ProtobufCodec.CODEC_ID) {
      respBody = PullProto.PullResp.newBuilder()
        .setCode(0).setMessage("success").setHasMore(false).build();
    } else {
      respBody = jsonBody().put("code", 0).put("message", "success")
        .put("hasMore", false).put("messages", new JsonArray());
    }
    return buildResponse(request, CMD_GROUP_PULL_MSG_RESP_VALUE, respBody);
  }
}
