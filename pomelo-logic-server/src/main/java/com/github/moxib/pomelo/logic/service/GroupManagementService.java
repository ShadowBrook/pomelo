package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.logic.id.SnowflakeIdGenerator;
import com.github.moxib.pomelo.logic.infrastructure.GroupMsgReader;
import com.github.moxib.pomelo.logic.infrastructure.GroupRepository;
import com.github.moxib.pomelo.logic.infrastructure.MessageRepository;
import com.github.moxib.pomelo.logic.model.GroupInfo;
import com.github.moxib.pomelo.logic.model.GroupMemberRecord;
import com.github.moxib.pomelo.logic.model.requests.CreateGroupRequest;
import com.github.moxib.pomelo.logic.model.requests.GetGroupInfoRequest;
import com.github.moxib.pomelo.logic.model.requests.GetGroupMembersRequest;
import com.github.moxib.pomelo.logic.model.requests.GroupMsgReadRequest;
import com.github.moxib.pomelo.logic.model.requests.GroupReadStateRequest;
import com.github.moxib.pomelo.logic.model.requests.InviteToGroupRequest;
import com.github.moxib.pomelo.logic.model.requests.KickMemberRequest;
import com.github.moxib.pomelo.model.PushEnvelope;
import com.github.moxib.pomelo.proto.group.GroupMgmtProto;
import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.*;

public class GroupManagementService extends ServiceBase {

  private static final Logger LOG = LoggerFactory.getLogger(GroupManagementService.class);

  /** 群成员角色：普通成员 / 管理员 / 群主（与 im_group_member.role 一致） */
  private static final int ROLE_MEMBER = 0;
  private static final int ROLE_ADMIN = 1;
  private static final int ROLE_OWNER = 2;

  private final GroupRepository groupRepo;
  private final SnowflakeIdGenerator snowflake;
  private final PushRouter pushRouter;
  private final Map<Integer, Function<ImMessage, Future<ImMessage>>> dispatchMap;

  public GroupManagementService(PushRouter pushRouter,
                                GroupRepository groupRepo,
                                SnowflakeIdGenerator snowflake, MessageRepository messageRepo) {
    this.groupRepo = groupRepo;
    this.snowflake = snowflake;
    this.pushRouter = pushRouter;
    this.dispatchMap = Map.of(
      CMD_GROUP_CREATE_REQ_VALUE, this::handleCreateGroup,
      CMD_GROUP_INVITE_REQ_VALUE, this::handleInviteToGroup,
      CMD_GROUP_KICK_REQ_VALUE, this::handleKickMember,
      CMD_GROUP_GET_INFO_REQ_VALUE, this::handleGetGroupInfo,
      CMD_GROUP_GET_MEMBERS_REQ_VALUE, this::handleGetMembers,
      CMD_GROUP_GET_MY_GROUPS_REQ_VALUE, this::handleGetMyGroups,
      CMD_GROUP_MSG_READ_REQ_VALUE, this::handleGetMsgReadStatus,
      CMD_GROUP_READ_STATE_REQ_VALUE, this::handleGetGroupReadState
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

    CreateGroupRequest req = decode(message, CreateGroupRequest.class);
    if (req == null || req.name() == null || req.name().trim().isEmpty()) {
      return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_CREATE_RESP_VALUE,
        ErrorCode.BAD_REQUEST, "群名不能为空"));
    }
    String name = req.name();
    String avatar = req.avatar() != null ? req.avatar() : "";

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
      .compose(v -> groupRepo.addMember(snowflake.nextId(), id, ownerNumericId, ROLE_OWNER, now))
      .map(v -> {
        GroupMgmtProto.CreateGroupResp respBody = GroupMgmtProto.CreateGroupResp.newBuilder()
          .setCode(0).setMessage("success").setGroup(toProtoGroupInfo(group)).build();
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

    InviteToGroupRequest req = decode(message, InviteToGroupRequest.class);
    if (req == null || req.groupId() == null || req.groupId().isEmpty()
      || req.userId() == null || req.userId().isEmpty()) {
      return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_INVITE_RESP_VALUE,
        ErrorCode.BAD_REQUEST, "groupId 和 userId 不能为空"));
    }
    String groupId = req.groupId();
    String inviteeId = req.userId();

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
      return groupRepo.isMember(numericGroupId, operatorNumericId).compose(operatorIsMember -> {
        if (!operatorIsMember) {
          return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_INVITE_RESP_VALUE,
            ErrorCode.UNAUTHORIZED, "你不是该群成员，无权邀请"));
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
            return groupRepo.addMember(snowflake.nextId(), numericGroupId, inviteeNumericId, ROLE_MEMBER, now)
              .map(v -> {
                pushMemberChangeNotify(numericGroupId, inviteeNumericId, operatorNumericId,
                  GroupMgmtProto.GroupMemberChangeNotify.ChangeType.INVITED);
                GroupMgmtProto.InviteToGroupResp respBody = GroupMgmtProto.InviteToGroupResp.newBuilder()
                  .setCode(0).setMessage("success").build();
                LOG.info("成员已邀请: groupId={} invitee={}", groupId, inviteeId);
                return buildResponse(message, CMD_GROUP_INVITE_RESP_VALUE, respBody);
              });
          });
        });
      });
    });
  }

  private void pushMemberChangeNotify(long groupId, long userId, long operatorId,
                                      GroupMgmtProto.GroupMemberChangeNotify.ChangeType type) {
    groupRepo.findMembers(groupId)
      .onSuccess(members -> pushMemberChangeNotify(groupId, userId, operatorId, type, members))
      .onFailure(e -> LOG.warn("获取群成员失败 groupId={}: {}", groupId, e.getMessage()));
  }

  /**
   * 按给定的成员列表推送变更通知。
   * 移除成员时传入「移除前」的成员列表，让被移除者也收到通知（其客户端据此退群）。
   */
  private void pushMemberChangeNotify(long groupId, long userId, long operatorId,
                                      GroupMgmtProto.GroupMemberChangeNotify.ChangeType type,
                                      List<GroupMemberRecord> members) {
    GroupMgmtProto.GroupMemberChangeNotify notify = GroupMgmtProto.GroupMemberChangeNotify.newBuilder()
      .setGroupId(groupId)
      .setType(type)
      .setUserId(userId)
      .setOperatorId(operatorId)
      .build();
    byte[] body = notify.toByteArray();
    for (GroupMemberRecord member : members) {
      String targetUserId = String.valueOf(member.getUserId());
      PushEnvelope env = new PushEnvelope(targetUserId, CMD_GROUP_MEMBER_CHANGE_NOTIFY_VALUE, body);
      pushRouter.push(env);
    }
    LOG.debug("成员变更推送完成: groupId={} type={} userId={}", groupId, type, userId);
  }

  /**
   * 移除群成员。权限：群主可移除管理员与普通成员，管理员只能移除普通成员，群主不可被移除。
   * 被移除者也会收到 KICKED 推送，客户端据此退群。
   */
  private Future<ImMessage> handleKickMember(ImMessage message) {
    String operatorId = getUserIdFromHeaders(message);
    if (operatorId == null || operatorId.isEmpty()) {
      return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_KICK_RESP_VALUE,
        ErrorCode.UNAUTHORIZED, "未认证用户"));
    }

    KickMemberRequest req = decode(message, KickMemberRequest.class);
    if (req == null || req.groupId() == null || req.groupId().isEmpty()
      || req.userId() == null || req.userId().isEmpty()) {
      return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_KICK_RESP_VALUE,
        ErrorCode.BAD_REQUEST, "groupId 和 userId 不能为空"));
    }

    long numericGroupId = Long.parseLong(req.groupId());
    long targetNumericId = Long.parseLong(req.userId());
    long operatorNumericId = Long.parseLong(operatorId);
    if (targetNumericId == operatorNumericId) {
      return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_KICK_RESP_VALUE,
        ErrorCode.BAD_REQUEST, "不能移除自己"));
    }

    return groupRepo.findMembers(numericGroupId).compose(members -> {
      GroupMemberRecord operator = findMember(members, operatorNumericId);
      if (operator == null) {
        return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_KICK_RESP_VALUE,
          ErrorCode.UNAUTHORIZED, "你不是该群成员，无权移除"));
      }
      GroupMemberRecord target = findMember(members, targetNumericId);
      if (target == null) {
        return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_KICK_RESP_VALUE,
          ErrorCode.NOT_FOUND, "该用户不在群中"));
      }
      if (target.getRole() == ROLE_OWNER) {
        return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_KICK_RESP_VALUE,
          ErrorCode.UNAUTHORIZED, "不能移除群主"));
      }
      if (operator.getRole() <= target.getRole()) {
        return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_KICK_RESP_VALUE,
          ErrorCode.UNAUTHORIZED, "无权移除该成员"));
      }
      return groupRepo.removeMember(numericGroupId, targetNumericId).map(v -> {
        pushMemberChangeNotify(numericGroupId, targetNumericId, operatorNumericId,
          GroupMgmtProto.GroupMemberChangeNotify.ChangeType.KICKED, members);
        GroupMgmtProto.KickMemberResp respBody = GroupMgmtProto.KickMemberResp.newBuilder()
          .setCode(0).setMessage("success").build();
        LOG.info("成员已移除: groupId={} userId={} operator={}", numericGroupId, targetNumericId, operatorNumericId);
        return buildResponse(message, CMD_GROUP_KICK_RESP_VALUE, respBody);
      });
    });
  }

  private static GroupMemberRecord findMember(List<GroupMemberRecord> members, long userId) {
    for (GroupMemberRecord member : members) {
      if (member.getUserId() == userId) {
        return member;
      }
    }
    return null;
  }

  private Future<ImMessage> handleGetGroupInfo(ImMessage message) {
    GetGroupInfoRequest req = decode(message, GetGroupInfoRequest.class);
    if (req == null || req.groupId() == null || req.groupId().isEmpty()) {
      return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_GET_INFO_RESP_VALUE,
        ErrorCode.BAD_REQUEST, "groupId 不能为空"));
    }
    String groupId = req.groupId();

    return membershipDenial(message, CMD_GROUP_GET_INFO_RESP_VALUE, getUserIdFromHeaders(message), Long.parseLong(groupId))
      .compose(denial -> {
        if (denial != null) {
          return Future.succeededFuture(denial);
        }
        return groupRepo.findById(Long.parseLong(groupId))
          .compose(group -> {
            if (group == null) {
              return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_GET_INFO_RESP_VALUE,
                ErrorCode.NOT_FOUND, "群不存在"));
            }
            GroupMgmtProto.GetGroupInfoResp respBody = GroupMgmtProto.GetGroupInfoResp.newBuilder()
              .setCode(0).setMessage("success").setGroup(toProtoGroupInfo(group)).build();
            return Future.succeededFuture(buildResponse(message, CMD_GROUP_GET_INFO_RESP_VALUE, respBody));
          });
      });
  }

  private Future<ImMessage> handleGetMembers(ImMessage message) {
    GetGroupMembersRequest req = decode(message, GetGroupMembersRequest.class);
    if (req == null || req.groupId() == null || req.groupId().isEmpty()) {
      return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_GET_MEMBERS_RESP_VALUE,
        ErrorCode.BAD_REQUEST, "groupId 不能为空"));
    }
    String groupId = req.groupId();

    return membershipDenial(message, CMD_GROUP_GET_MEMBERS_RESP_VALUE, getUserIdFromHeaders(message), Long.parseLong(groupId))
      .compose(denial -> {
        if (denial != null) {
          return Future.succeededFuture(denial);
        }
        return groupRepo.findById(Long.parseLong(groupId)).compose(group -> {
          if (group == null) {
            return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_GET_MEMBERS_RESP_VALUE,
              ErrorCode.NOT_FOUND, "群不存在"));
          }
          return groupRepo.findMembers(group.getId())
            .compose(members -> {
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
              return Future.succeededFuture(
                buildResponse(message, CMD_GROUP_GET_MEMBERS_RESP_VALUE, resp.build()));
            });
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
        GroupMgmtProto.GetMyGroupsResp.Builder resp =
          GroupMgmtProto.GetMyGroupsResp.newBuilder().setCode(0).setMessage("success");
        for (GroupInfo g : groups) resp.addGroups(toProtoGroupInfo(g));
        return buildResponse(message, CMD_GROUP_GET_MY_GROUPS_RESP_VALUE, resp.build());
      });
  }

  private Future<ImMessage> handleGetMsgReadStatus(ImMessage message) {
    GroupMsgReadRequest req = decode(message, GroupMsgReadRequest.class);
    if (req == null || req.groupId() == null || req.groupId().isEmpty() || req.seq() <= 0) {
      return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_MSG_READ_RESP_VALUE,
        ErrorCode.BAD_REQUEST, "groupId 和 seq 不能为空"));
    }
    String groupId = req.groupId();
    long seq = req.seq();

    return membershipDenial(message, CMD_GROUP_MSG_READ_RESP_VALUE, getUserIdFromHeaders(message), Long.parseLong(groupId))
      .compose(denial -> {
        if (denial != null) {
          return Future.succeededFuture(denial);
        }
        return groupRepo.findById(Long.parseLong(groupId)).compose(group -> {
          if (group == null) {
            return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_MSG_READ_RESP_VALUE,
              ErrorCode.NOT_FOUND, "群不存在"));
          }
          return groupRepo.findMsgReaders(group.getId(), seq)
            .map(readers -> {
              GroupMgmtProto.GetGroupMsgReadStatusResp.Builder resp =
                GroupMgmtProto.GetGroupMsgReadStatusResp.newBuilder().setCode(0).setMessage("success");
              for (GroupMsgReader r : readers) {
                resp.addReaders(GroupMgmtProto.GroupMsgReader.newBuilder()
                  .setUserId(r.userId()).setNickname(nn(r.nickname())).setAvatar(nn(r.avatar())).build());
              }
              return buildResponse(message, CMD_GROUP_MSG_READ_RESP_VALUE, resp.build());
            });
        });
      });
  }

  /**
   * 全群成员已读游标：一次返回 user_id → last_read_seq，客户端本地计算各消息已读人数。
   */
  private Future<ImMessage> handleGetGroupReadState(ImMessage message) {
    GroupReadStateRequest req = decode(message, GroupReadStateRequest.class);
    if (req == null || req.groupId() == null || req.groupId().isEmpty()) {
      return Future.succeededFuture(buildErrorResp(message, CMD_GROUP_READ_STATE_RESP_VALUE,
        ErrorCode.BAD_REQUEST, "groupId 不能为空"));
    }
    long groupId = Long.parseLong(req.groupId());

    return membershipDenial(message, CMD_GROUP_READ_STATE_RESP_VALUE, getUserIdFromHeaders(message), groupId)
      .compose(denial -> {
        if (denial != null) {
          return Future.succeededFuture(denial);
        }
        return groupRepo.findMemberReadStates(groupId)
          .map(states -> {
            GroupMgmtProto.GetGroupReadStateResp.Builder resp =
              GroupMgmtProto.GetGroupReadStateResp.newBuilder().setCode(0).setMessage("success");
            states.forEach((userId, lastReadSeq) -> resp.addMembers(
              GroupMgmtProto.MemberReadState.newBuilder()
                .setUserId(userId).setLastReadSeq(lastReadSeq).build()));
            return buildResponse(message, CMD_GROUP_READ_STATE_RESP_VALUE, resp.build());
          });
      });
  }

  /**
   * 群信息类接口的成员资格门槛：未认证、群不存在或非成员时返回错误响应（非 null），
   * 通过校验时返回 null，调用方继续原处理。
   */
  private Future<ImMessage> membershipDenial(ImMessage message, int respCmd, String userId, long groupId) {
    if (userId == null || userId.isEmpty()) {
      return Future.succeededFuture(buildErrorResp(message, respCmd, ErrorCode.UNAUTHORIZED, "未认证用户"));
    }
    long numericUserId;
    try {
      numericUserId = Long.parseLong(userId);
    } catch (NumberFormatException e) {
      return Future.succeededFuture(buildErrorResp(message, respCmd, ErrorCode.UNAUTHORIZED, "无效用户"));
    }
    return groupRepo.findById(groupId).compose(group -> {
      if (group == null) {
        return Future.succeededFuture(buildErrorResp(message, respCmd, ErrorCode.NOT_FOUND, "群不存在"));
      }
      return groupRepo.isMember(groupId, numericUserId)
        .map(isMember -> isMember ? null
          : buildErrorResp(message, respCmd, ErrorCode.UNAUTHORIZED, "你不是该群成员"));
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
}
