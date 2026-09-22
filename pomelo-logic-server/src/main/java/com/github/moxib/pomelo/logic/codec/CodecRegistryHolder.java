package com.github.moxib.pomelo.logic.codec;

import com.github.moxib.pomelo.codec.CodecRegistry;
import com.github.moxib.pomelo.logic.model.requests.AckRequest;
import com.github.moxib.pomelo.logic.model.requests.C2CRequest;
import com.github.moxib.pomelo.logic.model.requests.C2GRequest;
import com.github.moxib.pomelo.logic.model.requests.CallAcceptRequest;
import com.github.moxib.pomelo.logic.model.requests.CallEndRequest;
import com.github.moxib.pomelo.logic.model.requests.CallInviteRequest;
import com.github.moxib.pomelo.logic.model.requests.CallTokenRequest;
import com.github.moxib.pomelo.logic.model.requests.CreateGroupRequest;
import com.github.moxib.pomelo.logic.model.requests.CtrlRequest;
import com.github.moxib.pomelo.logic.model.requests.DissolveGroupRequest;
import com.github.moxib.pomelo.logic.model.requests.FriendOpRequest;
import com.github.moxib.pomelo.logic.model.requests.GetGroupInfoRequest;
import com.github.moxib.pomelo.logic.model.requests.GetGroupMembersRequest;
import com.github.moxib.pomelo.logic.model.requests.GroupAckRequest;
import com.github.moxib.pomelo.logic.model.requests.GroupMsgReadRequest;
import com.github.moxib.pomelo.logic.model.requests.GroupReadStateRequest;
import com.github.moxib.pomelo.logic.model.requests.GroupPullMsgRequest;
import com.github.moxib.pomelo.logic.model.requests.InviteToGroupRequest;
import com.github.moxib.pomelo.logic.model.requests.KickMemberRequest;
import com.github.moxib.pomelo.logic.model.requests.LoginRequest;
import com.github.moxib.pomelo.logic.model.requests.PullRequest;
import com.github.moxib.pomelo.logic.model.requests.SearchRequest;
import com.github.moxib.pomelo.logic.model.requests.TransferGroupRequest;
import com.github.moxib.pomelo.logic.model.requests.UpdateGroupRequest;
import com.github.moxib.pomelo.logic.model.requests.UploadRequest;
import com.github.moxib.pomelo.proto.ack.AckProto;
import com.github.moxib.pomelo.proto.auth.AuthProto;
import com.github.moxib.pomelo.proto.call.CallProto;
import com.github.moxib.pomelo.proto.chat.ChatProto;
import com.github.moxib.pomelo.proto.ctrl.CtrlProto;
import com.github.moxib.pomelo.proto.group.GroupMgmtProto;
import com.github.moxib.pomelo.proto.group.GroupProto;
import com.github.moxib.pomelo.proto.pull.PullProto;
import com.github.moxib.pomelo.proto.relation.RelationProto;
import com.github.moxib.pomelo.proto.upload.UploadProto;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.*;

/**
 * 全局共享的 CodecRegistry — 所有请求 cmd 的 Protobuf 编解码器在此集中注册。
 * 各 service 通过 {@link #REGISTRY} 使用，不再各自 new 实例。
 * 后端为 Protobuf 单编解码（codecId 冻结为 0），不再注册 JSON 编解码器。
 */
public final class CodecRegistryHolder {

  /** 全局共享编解码器注册表（线程安全，单例） */
  public static final CodecRegistry REGISTRY = build();

  private CodecRegistryHolder() {
  }

  private static CodecRegistry build() {
    CodecRegistry r = new CodecRegistry();

    // 认证
    r.registerProtobuf(CMD_AUTH_REQ_VALUE, AuthProto.AuthReq.parser(),
      LoginRequest::fromProto, LoginRequest.class);

    // C2C 单聊
    r.registerProtobuf(CMD_C2C_REQ_VALUE, ChatProto.C2CReq.parser(),
      C2CRequest::fromProto, C2CRequest.class);

    // C2G 群消息发送
    r.registerProtobuf(CMD_C2G_REQ_VALUE, GroupProto.C2GReq.parser(),
      C2GRequest::fromProto, C2GRequest.class);

    // 消息拉取（单聊离线/历史 + 群消息）
    r.registerProtobuf(CMD_PULL_REQ_VALUE, PullProto.PullReq.parser(),
      PullRequest::fromProto, PullRequest.class);
    r.registerProtobuf(CMD_GROUP_PULL_MSG_REQ_VALUE, PullProto.PullGroupMsgReq.parser(),
      GroupPullMsgRequest::fromProto, GroupPullMsgRequest.class);

    // 已读回执（单聊 + 群聊）
    r.registerProtobuf(CMD_ACK_REQ_VALUE, AckProto.AckReq.parser(),
      AckRequest::fromProto, AckRequest.class);
    r.registerProtobuf(CMD_GROUP_ACK_REQ_VALUE, GroupMgmtProto.GroupAckReq.parser(),
      GroupAckRequest::fromProto, GroupAckRequest.class);

    // 控制信令
    r.registerProtobuf(CMD_CTRL_REQ_VALUE, CtrlProto.CtrlReq.parser(),
      CtrlRequest::fromProto, CtrlRequest.class);

    // 好友
    r.registerProtobuf(CMD_FRIEND_SEARCH_REQ_VALUE, RelationProto.SearchUserReq.parser(),
      SearchRequest::fromProto, SearchRequest.class);
    r.registerProtobuf(CMD_FRIEND_ADD_REQ_VALUE, RelationProto.FriendAddReq.parser(),
      FriendOpRequest::fromAddProto, FriendOpRequest.class);
    r.registerProtobuf(CMD_FRIEND_ACCEPT_REQ_VALUE, RelationProto.FriendAcceptReq.parser(),
      FriendOpRequest::fromAcceptProto, FriendOpRequest.class);
    r.registerProtobuf(CMD_FRIEND_DELETE_REQ_VALUE, RelationProto.FriendDeleteReq.parser(),
      FriendOpRequest::fromDeleteProto, FriendOpRequest.class);

    // 群管理
    r.registerProtobuf(CMD_GROUP_CREATE_REQ_VALUE,
      GroupMgmtProto.CreateGroupReq.parser(), CreateGroupRequest::fromProto, CreateGroupRequest.class);
    r.registerProtobuf(CMD_GROUP_INVITE_REQ_VALUE,
      GroupMgmtProto.InviteToGroupReq.parser(), InviteToGroupRequest::fromProto, InviteToGroupRequest.class);
    r.registerProtobuf(CMD_GROUP_KICK_REQ_VALUE,
      GroupMgmtProto.KickMemberReq.parser(), KickMemberRequest::fromProto, KickMemberRequest.class);
    r.registerProtobuf(CMD_GROUP_TRANSFER_REQ_VALUE,
      GroupMgmtProto.TransferGroupReq.parser(), TransferGroupRequest::fromProto, TransferGroupRequest.class);
    r.registerProtobuf(CMD_GROUP_DISSOLVE_REQ_VALUE,
      GroupMgmtProto.DissolveGroupReq.parser(), DissolveGroupRequest::fromProto, DissolveGroupRequest.class);
    r.registerProtobuf(CMD_GROUP_UPDATE_REQ_VALUE,
      GroupMgmtProto.UpdateGroupReq.parser(), UpdateGroupRequest::fromProto, UpdateGroupRequest.class);
    r.registerProtobuf(CMD_GROUP_GET_INFO_REQ_VALUE,
      GroupMgmtProto.GetGroupInfoReq.parser(), GetGroupInfoRequest::fromProto, GetGroupInfoRequest.class);
    r.registerProtobuf(CMD_GROUP_GET_MEMBERS_REQ_VALUE,
      GroupMgmtProto.GetGroupMembersReq.parser(), GetGroupMembersRequest::fromProto, GetGroupMembersRequest.class);
    r.registerProtobuf(CMD_GROUP_GET_MY_GROUPS_REQ_VALUE,
      GroupMgmtProto.GetMyGroupsReq.parser(), req -> null, Object.class);
    r.registerProtobuf(CMD_GROUP_MSG_READ_REQ_VALUE,
      GroupMgmtProto.GetGroupMsgReadStatusReq.parser(), GroupMsgReadRequest::fromProto, GroupMsgReadRequest.class);
    r.registerProtobuf(CMD_GROUP_READ_STATE_REQ_VALUE, GroupMgmtProto.GetGroupReadStateReq.parser(),
      GroupReadStateRequest::fromProto, GroupReadStateRequest.class);

    // 媒体上传预签名
    r.registerProtobuf(CMD_UPLOAD_REQ_VALUE, UploadProto.UploadReq.parser(),
      UploadRequest::fromProto, UploadRequest.class);

    // 音视频通话
    r.registerProtobuf(CMD_CALL_INVITE_REQ_VALUE, CallProto.CallInviteReq.parser(),
      CallInviteRequest::fromProto, CallInviteRequest.class);
    r.registerProtobuf(CMD_CALL_ACCEPT_REQ_VALUE, CallProto.CallAcceptReq.parser(),
      CallAcceptRequest::fromProto, CallAcceptRequest.class);
    r.registerProtobuf(CMD_CALL_END_REQ_VALUE, CallProto.CallEndReq.parser(),
      CallEndRequest::fromProto, CallEndRequest.class);
    r.registerProtobuf(CMD_CALL_TOKEN_REQ_VALUE, CallProto.CallTokenReq.parser(),
      CallTokenRequest::fromProto, CallTokenRequest.class);

    return r;
  }
}
