package com.github.moxib.pomelo.logic.codec;

import com.github.moxib.pomelo.codec.CodecRegistry;
import com.github.moxib.pomelo.logic.model.requests.AckRequest;
import com.github.moxib.pomelo.logic.model.requests.C2CRequest;
import com.github.moxib.pomelo.logic.model.requests.CtrlRequest;
import com.github.moxib.pomelo.logic.model.requests.FriendOpRequest;
import com.github.moxib.pomelo.logic.model.requests.LoginRequest;
import com.github.moxib.pomelo.logic.model.requests.PullRequest;
import com.github.moxib.pomelo.logic.model.requests.SearchRequest;
import com.github.moxib.pomelo.logic.model.requests.UploadRequest;
import com.github.moxib.pomelo.proto.ack.AckProto;
import com.github.moxib.pomelo.proto.auth.AuthProto;
import com.github.moxib.pomelo.proto.chat.ChatProto;
import com.github.moxib.pomelo.proto.ctrl.CtrlProto;
import com.github.moxib.pomelo.proto.group.GroupMgmtProto;
import com.github.moxib.pomelo.proto.group.GroupProto;
import com.github.moxib.pomelo.proto.pull.PullProto;
import com.github.moxib.pomelo.proto.relation.RelationProto;
import com.github.moxib.pomelo.proto.upload.UploadProto;
import io.vertx.core.json.JsonObject;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.*;

/**
 * 全局共享的 CodecRegistry — 所有请求 cmd 的 PB/JSON 编解码器在此集中注册。
 * 各 service 通过 {@link #REGISTRY} 使用，不再各自 new 实例。
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
    r.registerJson(CMD_AUTH_REQ_VALUE, LoginRequest.class);

    // C2C 单聊
    r.registerProtobuf(CMD_C2C_REQ_VALUE, ChatProto.C2CReq.parser(),
      C2CRequest::fromProto, C2CRequest.class);
    r.registerJson(CMD_C2C_REQ_VALUE, C2CRequest.class);

    // C2G 群消息发送
    r.registerProtobuf(CMD_C2G_REQ_VALUE, GroupProto.C2GReq.parser(),
      req -> null, Object.class);
    r.registerJson(CMD_C2G_REQ_VALUE, JsonObject.class);

    // 消息拉取（单聊离线/历史 + 群消息）
    r.registerProtobuf(CMD_PULL_REQ_VALUE, PullProto.PullReq.parser(),
      PullRequest::fromProto, PullRequest.class);
    r.registerJson(CMD_PULL_REQ_VALUE, PullRequest.class);
    r.registerProtobuf(CMD_GROUP_PULL_MSG_REQ_VALUE, PullProto.PullGroupMsgReq.parser(),
      req -> null, Object.class);
    r.registerJson(CMD_GROUP_PULL_MSG_REQ_VALUE, JsonObject.class);

    // 已读回执（单聊 + 群聊）
    r.registerProtobuf(CMD_ACK_REQ_VALUE, AckProto.AckReq.parser(),
      AckRequest::fromProto, AckRequest.class);
    r.registerJson(CMD_ACK_REQ_VALUE, AckRequest.class);
    r.registerProtobuf(CMD_GROUP_ACK_REQ_VALUE, GroupMgmtProto.GroupAckReq.parser(),
      req -> null, Object.class);
    r.registerJson(CMD_GROUP_ACK_REQ_VALUE, JsonObject.class);

    // 控制信令
    r.registerProtobuf(CMD_CTRL_REQ_VALUE, CtrlProto.CtrlReq.parser(),
      CtrlRequest::fromProto, CtrlRequest.class);
    r.registerJson(CMD_CTRL_REQ_VALUE, CtrlRequest.class);

    // 好友
    r.registerProtobuf(CMD_FRIEND_SEARCH_REQ_VALUE, RelationProto.SearchUserReq.parser(),
      SearchRequest::fromProto, SearchRequest.class);
    r.registerJson(CMD_FRIEND_SEARCH_REQ_VALUE, SearchRequest.class);
    r.registerProtobuf(CMD_FRIEND_ADD_REQ_VALUE, RelationProto.FriendAddReq.parser(),
      FriendOpRequest::fromAddProto, FriendOpRequest.class);
    r.registerJson(CMD_FRIEND_ADD_REQ_VALUE, FriendOpRequest.class);
    r.registerProtobuf(CMD_FRIEND_ACCEPT_REQ_VALUE, RelationProto.FriendAcceptReq.parser(),
      FriendOpRequest::fromAcceptProto, FriendOpRequest.class);
    r.registerJson(CMD_FRIEND_ACCEPT_REQ_VALUE, FriendOpRequest.class);
    r.registerProtobuf(CMD_FRIEND_DELETE_REQ_VALUE, RelationProto.FriendDeleteReq.parser(),
      FriendOpRequest::fromDeleteProto, FriendOpRequest.class);
    r.registerJson(CMD_FRIEND_DELETE_REQ_VALUE, FriendOpRequest.class);

    // 群管理
    r.registerJson(CMD_GROUP_CREATE_REQ_VALUE, JsonObject.class);
    r.registerJson(CMD_GROUP_INVITE_REQ_VALUE, JsonObject.class);
    r.registerJson(CMD_GROUP_GET_INFO_REQ_VALUE, JsonObject.class);
    r.registerJson(CMD_GROUP_GET_MEMBERS_REQ_VALUE, JsonObject.class);
    r.registerJson(CMD_GROUP_GET_MY_GROUPS_REQ_VALUE, JsonObject.class);
    r.registerJson(CMD_GROUP_MSG_READ_REQ_VALUE, JsonObject.class);
    r.registerProtobuf(CMD_GROUP_CREATE_REQ_VALUE,
      GroupMgmtProto.CreateGroupReq.parser(), req -> null, Object.class);
    r.registerProtobuf(CMD_GROUP_INVITE_REQ_VALUE,
      GroupMgmtProto.InviteToGroupReq.parser(), req -> null, Object.class);
    r.registerProtobuf(CMD_GROUP_GET_INFO_REQ_VALUE,
      GroupMgmtProto.GetGroupInfoReq.parser(), req -> null, Object.class);
    r.registerProtobuf(CMD_GROUP_GET_MEMBERS_REQ_VALUE,
      GroupMgmtProto.GetGroupMembersReq.parser(), req -> null, Object.class);
    r.registerProtobuf(CMD_GROUP_GET_MY_GROUPS_REQ_VALUE,
      GroupMgmtProto.GetMyGroupsReq.parser(), req -> null, Object.class);
    r.registerProtobuf(CMD_GROUP_MSG_READ_REQ_VALUE,
      GroupMgmtProto.GetGroupMsgReadStatusReq.parser(), req -> null, Object.class);

    // 媒体上传预签名
    r.registerProtobuf(CMD_UPLOAD_REQ_VALUE, UploadProto.UploadReq.parser(),
      UploadRequest::fromProto, UploadRequest.class);
    r.registerJson(CMD_UPLOAD_REQ_VALUE, UploadRequest.class);

    return r;
  }
}
