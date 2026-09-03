package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.logic.infrastructure.GroupMsgWithSender;
import com.github.moxib.pomelo.logic.infrastructure.GroupRepository;
import com.github.moxib.pomelo.logic.infrastructure.MessageRepository;
import com.github.moxib.pomelo.logic.model.UserIdInfo;
import com.github.moxib.pomelo.logic.model.requests.GroupPullMsgRequest;
import com.github.moxib.pomelo.proto.common.CommonProto;
import com.github.moxib.pomelo.proto.pull.PullProto;
import com.google.protobuf.ByteString;
import io.vertx.core.Future;
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
  private final MediaUrlSigner mediaUrlSigner;

  public GroupPullService(GroupRepository groupRepo, MessageRepository messageRepo,
                          MediaUrlSigner mediaUrlSigner) {
    this.groupRepo = groupRepo;
    this.messageRepo = messageRepo;
    this.mediaUrlSigner = mediaUrlSigner;
  }

  public Future<ImMessage> process(ImMessage message) {
    try {
      String userId = getUserIdFromHeaders(message);
      if (userId == null || userId.isEmpty()) {
        return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_PULL_MSG_RESP_VALUE,
          ErrorCode.UNAUTHORIZED, "未认证用户"));
      }

      GroupPullMsgRequest req = decode(message, GroupPullMsgRequest.class);
      if (req == null || req.groupId() == null || req.groupId().isEmpty()) {
        return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_PULL_MSG_RESP_VALUE,
          ErrorCode.BAD_REQUEST, "groupId 不能为空"));
      }
      String groupId = req.groupId();
      int limit = req.limit() > 0 ? req.limit() : DEFAULT_LIMIT;
      boolean backward = req.isBackward();
      long rawCursor = req.cursor();

      long cursor = backward && rawCursor <= 0 ? Long.MAX_VALUE : rawCursor;

      LOG.info("拉取群消息: userId={} groupId={} cursor={} limit={} backward={}",
        userId, groupId, cursor, limit, backward);

      long numericGroupId = Long.parseLong(groupId);
      return groupRepo.findById(numericGroupId).compose(group -> {
        if (group == null) {
          return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_PULL_MSG_RESP_VALUE,
            ErrorCode.NOT_FOUND, "群不存在"));
        }
        return groupRepo.pullMessages(numericGroupId, cursor, limit, backward)
          .compose(msgs -> buildPullResp(message, msgs, limit));
      });
    } catch (Exception e) {
      LOG.error("群消息拉取失败", e);
      return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_PULL_MSG_RESP_VALUE,
        ErrorCode.INTERNAL_ERROR, "拉取失败：" + e.getMessage()));
    }
  }

  private Future<ImMessage> buildPullResp(ImMessage request, List<GroupMsgWithSender> msgs, int limit) {
    if (msgs == null || msgs.isEmpty()) {
      return Future.succeededFuture(buildEmptyPullResp(request));
    }

    Set<Long> senderIds = msgs.stream()
      .map(GroupMsgWithSender::getSenderNumericId)
      .collect(Collectors.toSet());

    return messageRepo.findUserIdsByIds(new ArrayList<>(senderIds))
      .compose(idToInfo -> Future.succeededFuture(
        buildPullRespWithMessages(request, msgs, limit, idToInfo)))
      .recover(err -> Future.succeededFuture(
        buildPullRespWithMessages(request, msgs, limit, Collections.emptyMap())));
  }

  private ImMessage buildPullRespWithMessages(ImMessage request, List<GroupMsgWithSender> msgs, int limit,
                                               Map<Long, UserIdInfo> idToInfo) {
    PullProto.PullResp.Builder resp = PullProto.PullResp.newBuilder()
      .setCode(0).setMessage("success").setHasMore(msgs.size() >= limit);
    for (GroupMsgWithSender m : msgs) {
      UserIdInfo senderInfo = idToInfo.get(m.getSenderNumericId());
      String signedContent = mediaUrlSigner.signContent(m.getMsgType(), m.getContent());
      CommonProto.MessageContent.Builder mc = CommonProto.MessageContent.newBuilder()
        .setMsgTypeValue(m.getMsgType())
        .setContent(ByteString.copyFromUtf8(signedContent != null ? signedContent : ""))
        .setTimestamp(m.getCreatedAt());
      mc.putExt("id", String.valueOf(m.getId()));
      mc.putExt("senderId", senderInfo != null ? senderInfo.userId() : String.valueOf(m.getSenderNumericId()));
      mc.putExt("groupId", String.valueOf(m.getGroupId()));
      if (senderInfo != null && senderInfo.userName() != null) {
        mc.putExt("senderUserName", senderInfo.userName());
      }
      if (senderInfo != null && senderInfo.nickname() != null) {
        mc.putExt("senderNickname", senderInfo.nickname());
      }
      mc.putExt("seq", String.valueOf(m.getSeq()));
      resp.addMessages(mc);
    }
    return buildResponse(request, CMD_GROUP_PULL_MSG_RESP_VALUE, resp.build());
  }

  private ImMessage buildEmptyPullResp(ImMessage request) {
    PullProto.PullResp respBody = PullProto.PullResp.newBuilder()
      .setCode(0).setMessage("success").setHasMore(false).build();
    return buildResponse(request, CMD_GROUP_PULL_MSG_RESP_VALUE, respBody);
  }
}
