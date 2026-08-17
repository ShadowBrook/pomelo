package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.codec.ProtobufCodec;
import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.config.SessionRouteTable;
import com.github.moxib.pomelo.logic.id.SnowflakeIdGenerator;
import com.github.moxib.pomelo.logic.infrastructure.GroupMsgReader;
import com.github.moxib.pomelo.logic.infrastructure.GroupRepository;
import com.github.moxib.pomelo.logic.infrastructure.MessageRepository;
import com.github.moxib.pomelo.logic.model.GroupInfo;
import com.github.moxib.pomelo.logic.model.GroupMemberRecord;
import com.github.moxib.pomelo.model.PushEnvelope;
import com.github.moxib.pomelo.proto.group.GroupMgmtProto;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Function;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.*;

public class GroupManagementService extends ServiceBase {

  private static final Logger LOG = LoggerFactory.getLogger(GroupManagementService.class);

  private final GroupRepository groupRepo;
  private final SnowflakeIdGenerator snowflake;
  private final PushRouter pushRouter;
  private final SessionRouteTable routeTable;
  private final Map<Integer, Function<ImMessage, Future<ImMessage>>> dispatchMap;

  public GroupManagementService(PushRouter pushRouter,
                                GroupRepository groupRepo, SessionRouteTable routeTable,
                                SnowflakeIdGenerator snowflake, MessageRepository messageRepo) {
    this.groupRepo = groupRepo;
    this.snowflake = snowflake;
    this.pushRouter = pushRouter;
    this.routeTable = routeTable;
    this.dispatchMap = Map.of(
      CMD_GROUP_CREATE_REQ_VALUE, this::handleCreateGroup,
      CMD_GROUP_INVITE_REQ_VALUE, this::handleInviteToGroup,
      CMD_GROUP_GET_INFO_REQ_VALUE, this::handleGetGroupInfo,
      CMD_GROUP_GET_MEMBERS_REQ_VALUE, this::handleGetMembers,
      CMD_GROUP_GET_MY_GROUPS_REQ_VALUE, this::handleGetMyGroups,
      CMD_GROUP_MSG_READ_REQ_VALUE, this::handleGetMsgReadStatus
    );
  }

  public Future<ImMessage> process(ImMessage message) {
    int cmd = message.getCmd();
    try {
      Function<ImMessage, Future<ImMessage>> handler = dispatchMap.get(cmd);
      if (handler != null) {
        return handler.apply(message);
      }
      return Future.succeededFuture(buildErrorResp(message, CMD_ERROR_VALUE,
        ErrorCode.UNKNOWN_CMD, "不支持的群管理操作"));
    } catch (Exception e) {
      LOG.error("群管理操作失败 cmd=0x{}", Integer.toHexString(cmd), e);
      return Future.succeededFuture(buildErrorResp(message, CMD_ERROR_VALUE,
        ErrorCode.INTERNAL_ERROR, "处理失败：" + e.getMessage()));
    }
  }

  private Future<ImMessage> handleCreateGroup(ImMessage message) {
    String userId = getUserIdFromHeaders(message);
    if (userId == null || userId.isEmpty()) {
      return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_CREATE_RESP_VALUE,
        ErrorCode.UNAUTHORIZED, "未认证用户"));
    }

    String bodyStr = getBodyAsString(message);
    if (bodyStr == null) {
      return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_CREATE_RESP_VALUE,
        ErrorCode.BAD_REQUEST, "body 不能为空"));
    }
    JsonObject body = new JsonObject(bodyStr);
    String name = body.getString("name");
    if (name == null || name.trim().isEmpty()) {
      return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_CREATE_RESP_VALUE,
        ErrorCode.BAD_REQUEST, "群名不能为空"));
    }
    String avatar = body.getString("avatar", "");

    long id = snowflake.nextId();
    long ownerNumericId = Long.parseLong(userId);
    if (ownerNumericId == 0) {
      return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_CREATE_RESP_VALUE,
        ErrorCode.UNAUTHORIZED, "用户不存在"));
    }

    long now = System.currentTimeMillis();

    GroupInfo group = GroupInfo.builder()
      .id(id).name(name.trim()).avatar(avatar)
      .description("").ownerId(ownerNumericId)
      .memberCount(1).maxMembers(200)
      .createdAt(now).updatedAt(now)
      .build();

    return groupRepo.createGroup(group)
      .compose(v -> groupRepo.addMember(snowflake.nextId(), id, ownerNumericId, 2, now))
      .map(v -> {
        byte codecId = message.getCodecId();
        Object respBody;
        if (codecId == ProtobufCodec.CODEC_ID) {
          respBody = GroupMgmtProto.CreateGroupResp.newBuilder()
            .setCode(0).setMessage("success").setGroup(toProtoGroupInfo(group)).build();
        } else {
          respBody = jsonBody().put("code", 0).put("message", "success")
            .put("group", toJsonGroupInfo(group));
        }
        LOG.info("群创建成功: id={} name={} owner={}", id, name, userId);
        return buildResponse(message, CMD_GROUP_CREATE_RESP_VALUE, respBody);
      });
  }

  private Future<ImMessage> handleInviteToGroup(ImMessage message) {
    String operatorId = getUserIdFromHeaders(message);
    if (operatorId == null || operatorId.isEmpty()) {
      return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_INVITE_RESP_VALUE,
        ErrorCode.UNAUTHORIZED, "未认证用户"));
    }

    String bodyStr = getBodyAsString(message);
    if (bodyStr == null) {
      return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_INVITE_RESP_VALUE,
        ErrorCode.BAD_REQUEST, "body 不能为空"));
    }
    JsonObject body = new JsonObject(bodyStr);
    String groupId = body.getString("groupId");
    String inviteeId = body.getString("userId");
    if (groupId == null || groupId.isEmpty() || inviteeId == null || inviteeId.isEmpty()) {
      return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_INVITE_RESP_VALUE,
        ErrorCode.BAD_REQUEST, "groupId 和 userId 不能为空"));
    }

    long operatorNumericId = Long.parseLong(operatorId);
    long inviteeNumericId = Long.parseLong(inviteeId);
    if (inviteeNumericId == 0) {
      return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_INVITE_RESP_VALUE,
        ErrorCode.NOT_FOUND, "用户不存在"));
    }

    long numericGroupId = Long.parseLong(groupId);
    return groupRepo.findById(numericGroupId).compose(group -> {
      if (group == null) {
        return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_INVITE_RESP_VALUE,
          ErrorCode.NOT_FOUND, "群不存在"));
      }
      return groupRepo.isFriend(operatorNumericId, inviteeNumericId).compose(isFriend -> {
        if (!isFriend) {
          return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_INVITE_RESP_VALUE,
            ErrorCode.UNAUTHORIZED, "只能邀请好友入群"));
        }
        return groupRepo.isMember(numericGroupId, inviteeNumericId).compose(alreadyMember -> {
          if (alreadyMember) {
            return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_INVITE_RESP_VALUE,
              ErrorCode.CONFLICT, "用户已在群中"));
          }
          long now = System.currentTimeMillis();
          return groupRepo.addMember(snowflake.nextId(), numericGroupId, inviteeNumericId, 0, now)
            .map(v -> {
              pushMemberChangeNotify(numericGroupId, inviteeNumericId, operatorNumericId);
              byte codecId = message.getCodecId();
              Object respBody;
              if (codecId == ProtobufCodec.CODEC_ID) {
                respBody = GroupMgmtProto.InviteToGroupResp.newBuilder()
                  .setCode(0).setMessage("success").build();
              } else {
                respBody = jsonBody().put("code", 0).put("message", "success");
              }
              LOG.info("成员已邀请: groupId={} invitee={}", groupId, inviteeId);
              return buildResponse(message, CMD_GROUP_INVITE_RESP_VALUE, respBody);
            });
        });
      });
    });
  }

  private void pushMemberChangeNotify(long groupId, long inviteeId, long operatorId) {
    groupRepo.findMembers(groupId).onSuccess(members -> {
      for (GroupMemberRecord member : members) {
        String targetUserId = String.valueOf(member.getUserId());
        routeTable.resolveCodec(targetUserId).onSuccess(recipientCodec -> {
          byte[] pbBody;
          byte pushCodec;
          if (recipientCodec == ProtobufCodec.CODEC_ID) {
            GroupMgmtProto.GroupMemberChangeNotify notify = GroupMgmtProto.GroupMemberChangeNotify.newBuilder()
              .setGroupId(groupId)
              .setType(GroupMgmtProto.GroupMemberChangeNotify.ChangeType.INVITED)
              .setUserId(inviteeId)
              .setOperatorId(operatorId)
              .build();
            pbBody = notify.toByteArray();
            pushCodec = 0;
          } else {
            JsonObject json = new JsonObject();
            json.put("groupId", String.valueOf(groupId));
            json.put("type", "INVITED");
            json.put("userId", String.valueOf(inviteeId));
            json.put("operatorId", String.valueOf(operatorId));
            pbBody = json.toBuffer().getBytes();
            pushCodec = 1;
          }
          PushEnvelope env = new PushEnvelope(targetUserId, CMD_GROUP_MEMBER_CHANGE_NOTIFY_VALUE, pbBody, pushCodec);
          pushRouter.push(env);
        });
      }
      LOG.debug("成员变更推送完成: groupId={} type=INVITED invitee={}", groupId, inviteeId);
    }).onFailure(e -> LOG.warn("获取群成员失败 groupId={}: {}", groupId, e.getMessage()));
  }

  private Future<ImMessage> handleGetGroupInfo(ImMessage message) {
    String bodyStr = getBodyAsString(message);
    if (bodyStr == null) {
      return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_GET_INFO_RESP_VALUE,
        ErrorCode.BAD_REQUEST, "body 不能为空"));
    }
    JsonObject body = new JsonObject(bodyStr);
    String groupId = body.getString("groupId");

    return groupRepo.findById(Long.parseLong(groupId))
      .compose(group -> {
        if (group == null) {
          return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_GET_INFO_RESP_VALUE,
            ErrorCode.NOT_FOUND, "群不存在"));
        }
        byte codecId = message.getCodecId();
        Object respBody;
        if (codecId == ProtobufCodec.CODEC_ID) {
          respBody = GroupMgmtProto.GetGroupInfoResp.newBuilder()
            .setCode(0).setMessage("success").setGroup(toProtoGroupInfo(group)).build();
        } else {
          respBody = jsonBody().put("code", 0).put("message", "success")
            .put("group", toJsonGroupInfo(group));
        }
        return Future.succeededFuture(buildResponse(message, CMD_GROUP_GET_INFO_RESP_VALUE, respBody));
      });
  }

  private Future<ImMessage> handleGetMembers(ImMessage message) {
    String bodyStr = getBodyAsString(message);
    if (bodyStr == null) {
      return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_GET_MEMBERS_RESP_VALUE,
        ErrorCode.BAD_REQUEST, "body 不能为空"));
    }
    JsonObject body = new JsonObject(bodyStr);
    String groupId = body.getString("groupId");

    return groupRepo.findById(Long.parseLong(groupId)).compose(group -> {
      if (group == null) {
        return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_GET_MEMBERS_RESP_VALUE,
          ErrorCode.NOT_FOUND, "群不存在"));
      }
      return groupRepo.findMembers(group.getId())
        .compose(members -> {
        byte codecId = message.getCodecId();
        Object respBody;
        if (codecId == ProtobufCodec.CODEC_ID) {
          GroupMgmtProto.GetGroupMembersResp.Builder resp =
            GroupMgmtProto.GetGroupMembersResp.newBuilder().setCode(0).setMessage("success");
          for (GroupMemberRecord m : members) {
            resp.addMembers(GroupMgmtProto.GroupMember.newBuilder()
              .setUserId(m.getUserId())
              .setUserName(nn(m.getUserName()))
              .setNickname(nn(m.getNickname()))
              .setAvatar(nn(m.getAvatar()))
              .setRole(m.getRole()).setJoinedAt(m.getJoinedAt()).build());
          }
          respBody = resp.build();
        } else {
          JsonObject json = jsonBody().put("code", 0).put("message", "success");
          JsonArray arr = new JsonArray();
          json.put("members", arr);
          for (GroupMemberRecord m : members) {
            JsonObject jm = new JsonObject();
            jm.put("userId", String.valueOf(m.getUserId()));
            jm.put("userName", nn(m.getUserName()));
            jm.put("nickname", nn(m.getNickname()));
            jm.put("avatar", nn(m.getAvatar()));
            jm.put("role", m.getRole());
            jm.put("joinedAt", m.getJoinedAt());
            arr.add(jm);
          }
          respBody = json;
        }
        return Future.succeededFuture(buildResponse(message, CMD_GROUP_GET_MEMBERS_RESP_VALUE, respBody));
      });
      });
  }

  private Future<ImMessage> handleGetMyGroups(ImMessage message) {
    String userId = getUserIdFromHeaders(message);
    if (userId == null || userId.isEmpty()) {
      return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_GET_MY_GROUPS_RESP_VALUE,
        ErrorCode.UNAUTHORIZED, "未认证用户"));
    }

    long numericId = Long.parseLong(userId);
    if (numericId == 0) {
      return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_GET_MY_GROUPS_RESP_VALUE,
        ErrorCode.UNAUTHORIZED, "用户不存在"));
    }
    return groupRepo.findGroupsByUserId(numericId)
      .map(groups -> {
        byte codecId = message.getCodecId();
        Object respBody;
        if (codecId == ProtobufCodec.CODEC_ID) {
          GroupMgmtProto.GetMyGroupsResp.Builder resp =
            GroupMgmtProto.GetMyGroupsResp.newBuilder().setCode(0).setMessage("success");
          for (GroupInfo g : groups) resp.addGroups(toProtoGroupInfo(g));
          respBody = resp.build();
        } else {
          JsonObject json = jsonBody().put("code", 0).put("message", "success");
          JsonArray arr = new JsonArray();
          json.put("groups", arr);
          for (GroupInfo g : groups) arr.add(toJsonGroupInfo(g));
          respBody = json;
        }
        return buildResponse(message, CMD_GROUP_GET_MY_GROUPS_RESP_VALUE, respBody);
      });
  }

  private Future<ImMessage> handleGetMsgReadStatus(ImMessage message) {
    String bodyStr = getBodyAsString(message);
    if (bodyStr == null) {
      return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_MSG_READ_RESP_VALUE,
        ErrorCode.BAD_REQUEST, "body 不能为空"));
    }
    JsonObject body = new JsonObject(bodyStr);
    String groupId = body.getString("groupId");
    long seq = body.getLong("seq", 0L);
    if (groupId == null || groupId.isEmpty() || seq <= 0) {
      return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_MSG_READ_RESP_VALUE,
        ErrorCode.BAD_REQUEST, "groupId 和 seq 不能为空"));
    }

    return groupRepo.findById(Long.parseLong(groupId)).compose(group -> {
      if (group == null) {
        return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_MSG_READ_RESP_VALUE,
          ErrorCode.NOT_FOUND, "群不存在"));
      }
      return groupRepo.findMsgReaders(group.getId(), seq)
        .map(readers -> {
        byte codecId = message.getCodecId();
        Object respBody;
        if (codecId == ProtobufCodec.CODEC_ID) {
          GroupMgmtProto.GetGroupMsgReadStatusResp.Builder resp =
            GroupMgmtProto.GetGroupMsgReadStatusResp.newBuilder().setCode(0).setMessage("success");
          for (GroupMsgReader r : readers) {
            resp.addReaders(GroupMgmtProto.GroupMsgReader.newBuilder()
              .setUserId(r.userId()).setNickname(nn(r.nickname())).setAvatar(nn(r.avatar())).build());
          }
          respBody = resp.build();
        } else {
          JsonObject json = jsonBody().put("code", 0).put("message", "success");
          JsonArray arr = new JsonArray();
          json.put("readers", arr);
          for (GroupMsgReader r : readers) {
            JsonObject jr = new JsonObject();
            jr.put("userId", String.valueOf(r.userId()));
            jr.put("nickname", nn(r.nickname()));
            jr.put("avatar", nn(r.avatar()));
            arr.add(jr);
          }
          respBody = json;
        }
        return buildResponse(message, CMD_GROUP_MSG_READ_RESP_VALUE, respBody);
      });
      });
  }

  private static String nn(String s) { return s != null ? s : ""; }

  private GroupMgmtProto.GroupInfo toProtoGroupInfo(GroupInfo g) {
    return GroupMgmtProto.GroupInfo.newBuilder()
      .setGroupId(g.getId())
      .setName(g.getName())
      .setAvatar(nn(g.getAvatar())).setDescription(nn(g.getDescription()))
      .setOwnerId(g.getOwnerId()).setMemberCount(g.getMemberCount())
      .setMaxMembers(g.getMaxMembers()).setCreatedAt(g.getCreatedAt())
      .build();
  }

  private JsonObject toJsonGroupInfo(GroupInfo g) {
    return new JsonObject()
      .put("groupId", String.valueOf(g.getId()))
      .put("name", g.getName())
      .put("avatar", nn(g.getAvatar())).put("description", nn(g.getDescription()))
      .put("ownerId", String.valueOf(g.getOwnerId())).put("memberCount", g.getMemberCount())
      .put("maxMembers", g.getMaxMembers()).put("createdAt", g.getCreatedAt())
      .put("updatedAt", g.getUpdatedAt());
  }
}
