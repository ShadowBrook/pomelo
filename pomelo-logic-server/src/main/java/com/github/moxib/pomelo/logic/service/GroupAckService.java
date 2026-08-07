package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.codec.ProtobufCodec;
import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.logic.infrastructure.GroupRepository;
import com.github.moxib.pomelo.logic.infrastructure.MessageRepository;
import com.github.moxib.pomelo.proto.group.GroupMgmtProto;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.*;

public class GroupAckService extends ServiceBase {

  private static final Logger LOG = LoggerFactory.getLogger(GroupAckService.class);

  private final GroupRepository groupRepo;
  private final MessageRepository messageRepo;

  public GroupAckService(GroupRepository groupRepo, MessageRepository messageRepo) {
    this.groupRepo = groupRepo;
    this.messageRepo = messageRepo;
  }

  public Future<ImMessage> process(ImMessage message) {
    try {
      String userId = getUserIdFromHeaders(message);
      if (userId == null || userId.isEmpty()) {
        return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_ACK_RESP_VALUE,
          ErrorCode.UNAUTHORIZED, "未认证用户"));
      }

      String bodyStr = getBodyAsString(message);
      if (bodyStr == null) {
        return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_ACK_RESP_VALUE,
          ErrorCode.BAD_REQUEST, "body 不能为空"));
      }
      JsonObject body = new JsonObject(bodyStr);
      String groupId = body.getString("groupId");
      long lastReadSeq = body.getLong("lastReadSeq", 0L);

      if (groupId == null || groupId.isEmpty()) {
        return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_ACK_RESP_VALUE,
          ErrorCode.BAD_REQUEST, "groupId 不能为空"));
      }

      return resolveId(userId).compose(numericId -> {
        if (numericId == 0) {
          return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_ACK_RESP_VALUE,
            ErrorCode.UNAUTHORIZED, "用户不存在"));
        }
        LOG.debug("群 ACK: userId={} groupId={} lastReadSeq={}", userId, groupId, lastReadSeq);
      return groupRepo.isMember(groupId, numericId).compose(isMember -> {
          if (!isMember) {
            return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_ACK_RESP_VALUE,
              ErrorCode.UNAUTHORIZED, "你不是该群成员"));
          }
          return groupRepo.updateLastReadSeq(groupId, numericId, lastReadSeq)
            .map(v -> {
              byte codecId = message.getCodecId();
              Object respBody;
              if (codecId == ProtobufCodec.CODEC_ID) {
                respBody = GroupMgmtProto.GroupAckResp.newBuilder()
                  .setCode(0).setMessage("success").build();
              } else {
                respBody = jsonBody().put("code", 0).put("message", "success");
              }
              return buildResponse(message, CMD_GROUP_ACK_RESP_VALUE, respBody);
            });
        });
      });
    } catch (Exception e) {
      LOG.error("群 ACK 处理失败", e);
      return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_ACK_RESP_VALUE,
        ErrorCode.INTERNAL_ERROR, "处理失败：" + e.getMessage()));
    }
  }

  private Future<Long> resolveId(String userId) {
    try { return Future.succeededFuture(Long.parseLong(userId)); }
    catch (NumberFormatException e) { return messageRepo.findUserId(userId); }
  }
}
