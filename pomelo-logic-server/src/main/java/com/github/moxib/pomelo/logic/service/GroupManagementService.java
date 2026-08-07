package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.codec.CodecRegistry;
import com.github.moxib.pomelo.codec.ProtobufCodec;
import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.config.SessionRouteTable;
import com.github.moxib.pomelo.logic.id.SnowflakeIdGenerator;
import com.github.moxib.pomelo.logic.infrastructure.GroupRepository;
import com.github.moxib.pomelo.logic.infrastructure.MessageRepository;
import com.github.moxib.pomelo.logic.model.GroupInfo;
import com.github.moxib.pomelo.logic.model.GroupMemberRecord;
import com.github.moxib.pomelo.proto.group.GroupMgmtProto;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.moxib.pomelo.logic.id.NanoIdGenerator;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.*;

public class GroupManagementService extends ServiceBase {

  private static final Logger LOG = LoggerFactory.getLogger(GroupManagementService.class);

  private final PushRouter pushRouter;
  private final GroupRepository groupRepo;
  private final MessageRepository messageRepo;
  private final SessionRouteTable routeTable;
  private final SnowflakeIdGenerator snowflake;
  private final CodecRegistry codecRegistry;

  public GroupManagementService(Vertx vertx, PushRouter pushRouter,
                                 GroupRepository groupRepo, SessionRouteTable routeTable,
                                 SnowflakeIdGenerator snowflake, MessageRepository messageRepo) {
    this.pushRouter = pushRouter;
    this.groupRepo = groupRepo;
    this.messageRepo = messageRepo;
    this.routeTable = routeTable;
    this.snowflake = snowflake;
    this.codecRegistry = new CodecRegistry();

    codecRegistry.registerJson(CMD_GROUP_CREATE_REQ_VALUE, JsonObject.class);
    codecRegistry.registerJson(CMD_GROUP_GET_INFO_REQ_VALUE, JsonObject.class);
    codecRegistry.registerJson(CMD_GROUP_GET_MEMBERS_REQ_VALUE, JsonObject.class);
    codecRegistry.registerJson(CMD_GROUP_GET_MY_GROUPS_REQ_VALUE, JsonObject.class);

    codecRegistry.registerProtobuf(CMD_GROUP_CREATE_REQ_VALUE,
      GroupMgmtProto.CreateGroupReq.parser(), req -> null, Object.class);
    codecRegistry.registerProtobuf(CMD_GROUP_GET_INFO_REQ_VALUE,
      GroupMgmtProto.GetGroupInfoReq.parser(), req -> null, Object.class);
    codecRegistry.registerProtobuf(CMD_GROUP_GET_MEMBERS_REQ_VALUE,
      GroupMgmtProto.GetGroupMembersReq.parser(), req -> null, Object.class);
    codecRegistry.registerProtobuf(CMD_GROUP_GET_MY_GROUPS_REQ_VALUE,
      GroupMgmtProto.GetMyGroupsReq.parser(), req -> null, Object.class);
  }

  public Future<ImMessage> process(ImMessage message) {
    int cmd = message.getCmd();
    try {
      if (cmd == CMD_GROUP_CREATE_REQ_VALUE) return handleCreateGroup(message);
      if (cmd == CMD_GROUP_GET_INFO_REQ_VALUE) return handleGetGroupInfo(message);
      if (cmd == CMD_GROUP_GET_MEMBERS_REQ_VALUE) return handleGetMembers(message);
      if (cmd == CMD_GROUP_GET_MY_GROUPS_REQ_VALUE) return handleGetMyGroups(message);
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
    String groupId = genNanoId();
    long now = System.currentTimeMillis();

    return resolveId(userId).compose(ownerNumericId -> {
      if (ownerNumericId == 0) {
        return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_CREATE_RESP_VALUE,
          ErrorCode.UNAUTHORIZED, "用户不存在"));
      }

      GroupInfo group = GroupInfo.builder()
        .id(id).groupId(groupId).name(name.trim()).avatar(avatar)
        .description("").ownerId(userId)
        .memberCount(1).maxMembers(200)
        .createdAt(now).updatedAt(now)
        .build();

      return groupRepo.createGroup(group)
        .compose(v -> groupRepo.addMember(snowflake.nextId(), groupId, ownerNumericId, 2, now))
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
          LOG.info("群创建成功: id={} groupId={} name={} owner={}", id, groupId, name, userId);
          return buildResponse(message, CMD_GROUP_CREATE_RESP_VALUE, respBody);
        });
    });
  }

  private Future<ImMessage> handleGetGroupInfo(ImMessage message) {
    String bodyStr = getBodyAsString(message);
    if (bodyStr == null) {
      return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_GET_INFO_RESP_VALUE,
        ErrorCode.BAD_REQUEST, "body 不能为空"));
    }
    JsonObject body = new JsonObject(bodyStr);
    String groupId = body.getString("groupId");

    return groupRepo.findByGroupId(groupId)
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

    return groupRepo.findMembers(groupId)
      .compose(members -> {
        byte codecId = message.getCodecId();
        Object respBody;
        if (codecId == ProtobufCodec.CODEC_ID) {
          GroupMgmtProto.GetGroupMembersResp.Builder resp =
            GroupMgmtProto.GetGroupMembersResp.newBuilder().setCode(0).setMessage("success");
          for (GroupMemberRecord m : members) {
            resp.addMembers(GroupMgmtProto.GroupMember.newBuilder()
              .setUserId(nn(m.getUserName()))
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
            jm.put("userId", nn(m.getUserName()));
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
  }

  private Future<ImMessage> handleGetMyGroups(ImMessage message) {
    String userId = getUserIdFromHeaders(message);
    if (userId == null || userId.isEmpty()) {
      return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_GET_MY_GROUPS_RESP_VALUE,
        ErrorCode.UNAUTHORIZED, "未认证用户"));
    }

    return resolveId(userId).compose(numericId -> {
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
    });
  }

  private Future<Long> resolveId(String userId) {
    try { return Future.succeededFuture(Long.parseLong(userId)); }
    catch (NumberFormatException e) { return messageRepo.findUserId(userId); }
  }

  private static String genNanoId() {
    return NanoIdGenerator.next();
  }

  private static String nn(String s) { return s != null ? s : ""; }

  private GroupMgmtProto.GroupInfo toProtoGroupInfo(GroupInfo g) {
    return GroupMgmtProto.GroupInfo.newBuilder()
      .setGroupId(g.getGroupId())
      .setName(g.getName())
      .setAvatar(nn(g.getAvatar())).setDescription(nn(g.getDescription()))
      .setOwnerId(g.getOwnerId()).setMemberCount(g.getMemberCount())
      .setMaxMembers(g.getMaxMembers()).setCreatedAt(g.getCreatedAt())
      .build();
  }

  private JsonObject toJsonGroupInfo(GroupInfo g) {
    return new JsonObject()
      .put("groupId", g.getGroupId())
      .put("name", g.getName())
      .put("avatar", nn(g.getAvatar())).put("description", nn(g.getDescription()))
      .put("ownerId", g.getOwnerId()).put("memberCount", g.getMemberCount())
      .put("maxMembers", g.getMaxMembers()).put("createdAt", g.getCreatedAt())
      .put("updatedAt", g.getUpdatedAt());
  }
}
