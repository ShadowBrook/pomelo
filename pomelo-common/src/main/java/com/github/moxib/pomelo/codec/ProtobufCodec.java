package com.github.moxib.pomelo.codec;

import com.github.moxib.pomelo.proto.ack.AckProto;
import com.github.moxib.pomelo.proto.auth.AuthProto;
import com.github.moxib.pomelo.proto.chat.ChatProto;
import com.github.moxib.pomelo.proto.common.CommonProto;
import com.github.moxib.pomelo.proto.ctrl.CtrlProto;
import com.github.moxib.pomelo.proto.group.GroupProto;
import com.github.moxib.pomelo.proto.heartbeat.HeartbeatProto;
import com.github.moxib.pomelo.proto.pull.PullProto;
import com.github.moxib.pomelo.proto.group.GroupMgmtProto;
import com.github.moxib.pomelo.proto.relation.RelationProto;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;

import java.util.function.Function;

/**
 * Protobuf 编解码器 — 支持两种模式：
 *
 * 1. Raw proto 模式（无 mapper）：decode 返回 proto Message 对象，encode 输出 toByteArray()
 * 2. DTO 映射模式（有 mapper）：decode 先解析 PB 再通过 mapper 转为 DTO record，
 *    encode 不支持（响应编码走 {@code encodeBody()/buildResponse()} 的 JsonFormat 路径）
 *
 * 静态 PARSER_REGISTRY 通过 cmd 索引 parser，避免反射。
 */
public class ProtobufCodec<T> implements MessageCodec<T> {

  public static final byte CODEC_ID = 0;

  /**
   * 判断 codecId 是否为 Protobuf
   */
  public static boolean isProtobuf(byte codecId) {
    return codecId == CODEC_ID;
  }

  /**
   * Cmd 到 Parser 的映射表（cmd 值范围 0–255）
   */
  private static final Parser<?>[] PARSER_REGISTRY = new Parser[256];

  /**
   * Cmd 到 proto Message 类型的映射表（cmd 值范围 0–255）
   */
  @SuppressWarnings("unchecked")
  private static final Class<? extends Message>[] PROTO_TYPE_REGISTRY = new Class[256];

  /**
   * 超出 0–255 范围的 cmd 单独存储（如 CMD_ERROR = 0xFFFF）
   */
  private static final java.util.Map<Integer, Parser<?>> OVERFLOW_PARSERS = new java.util.HashMap<>();
  private static final java.util.Map<Integer, Class<? extends Message>> OVERFLOW_TYPES = new java.util.HashMap<>();

  static {
    // 认证相关
    registerProto(CommonProto.Cmd.CMD_AUTH_REQ_VALUE, AuthProto.AuthReq.parser(), AuthProto.AuthReq.class);
    registerProto(CommonProto.Cmd.CMD_AUTH_RESP_VALUE, AuthProto.AuthResp.parser(), AuthProto.AuthResp.class);
    registerProto(CommonProto.Cmd.CMD_LOGOUT_REQ_VALUE, AuthProto.LogoutReq.parser(), AuthProto.LogoutReq.class);
    registerProto(CommonProto.Cmd.CMD_LOGOUT_RESP_VALUE, AuthProto.LogoutResp.parser(), AuthProto.LogoutResp.class);

    // 单聊相关
    registerProto(CommonProto.Cmd.CMD_C2C_REQ_VALUE, ChatProto.C2CReq.parser(), ChatProto.C2CReq.class);
    registerProto(CommonProto.Cmd.CMD_C2C_RESP_VALUE, ChatProto.C2CResp.parser(), ChatProto.C2CResp.class);
    registerProto(CommonProto.Cmd.CMD_C2C_NOTIFY_VALUE, ChatProto.C2CNotify.parser(), ChatProto.C2CNotify.class);

    // 群聊相关
    registerProto(CommonProto.Cmd.CMD_C2G_REQ_VALUE, GroupProto.C2GReq.parser(), GroupProto.C2GReq.class);
    registerProto(CommonProto.Cmd.CMD_C2G_RESP_VALUE, GroupProto.C2GResp.parser(), GroupProto.C2GResp.class);
    registerProto(CommonProto.Cmd.CMD_C2G_NOTIFY_VALUE, GroupProto.C2GNotify.parser(), GroupProto.C2GNotify.class);

    // 消息拉取
    registerProto(CommonProto.Cmd.CMD_PULL_REQ_VALUE, PullProto.PullReq.parser(), PullProto.PullReq.class);
    registerProto(CommonProto.Cmd.CMD_PULL_RESP_VALUE, PullProto.PullResp.parser(), PullProto.PullResp.class);

    // 控制命令
    registerProto(CommonProto.Cmd.CMD_CTRL_REQ_VALUE, CtrlProto.CtrlReq.parser(), CtrlProto.CtrlReq.class);
    registerProto(CommonProto.Cmd.CMD_CTRL_RESP_VALUE, CtrlProto.CtrlResp.parser(), CtrlProto.CtrlResp.class);
    registerProto(CommonProto.Cmd.CMD_CTRL_NOTIFY_VALUE, CtrlProto.CtrlNotify.parser(), CtrlProto.CtrlNotify.class);

    // 心跳与 ACK
    registerProto(CommonProto.Cmd.CMD_PING_VALUE, HeartbeatProto.Ping.parser(), HeartbeatProto.Ping.class);
    registerProto(CommonProto.Cmd.CMD_PONG_VALUE, HeartbeatProto.Pong.parser(), HeartbeatProto.Pong.class);
    registerProto(CommonProto.Cmd.CMD_ACK_REQ_VALUE, AckProto.AckReq.parser(), AckProto.AckReq.class);
    registerProto(CommonProto.Cmd.CMD_ACK_RESP_VALUE, AckProto.AckResp.parser(), AckProto.AckResp.class);
    registerProto(CommonProto.Cmd.CMD_ACK_NOTIFY_VALUE, AckProto.AckNotify.parser(), AckProto.AckNotify.class);

    // 通用错误响应（0xFFFF 超出 256 范围，存 overflow map）
    registerProto(CommonProto.Cmd.CMD_ERROR_VALUE, CommonProto.ErrorBody.parser(), CommonProto.ErrorBody.class);

    // 好友关系
    registerProto(CommonProto.Cmd.CMD_FRIEND_SEARCH_REQ_VALUE, RelationProto.SearchUserReq.parser(), RelationProto.SearchUserReq.class);
    registerProto(CommonProto.Cmd.CMD_FRIEND_SEARCH_RESP_VALUE, RelationProto.SearchUserResp.parser(), RelationProto.SearchUserResp.class);
    registerProto(CommonProto.Cmd.CMD_FRIEND_ADD_REQ_VALUE, RelationProto.FriendAddReq.parser(), RelationProto.FriendAddReq.class);
    registerProto(CommonProto.Cmd.CMD_FRIEND_ADD_RESP_VALUE, RelationProto.FriendAddResp.parser(), RelationProto.FriendAddResp.class);
    registerProto(CommonProto.Cmd.CMD_FRIEND_ADD_NOTIFY_VALUE, RelationProto.FriendAddNotify.parser(), RelationProto.FriendAddNotify.class);
    registerProto(CommonProto.Cmd.CMD_FRIEND_ACCEPT_REQ_VALUE, RelationProto.FriendAcceptReq.parser(), RelationProto.FriendAcceptReq.class);
    registerProto(CommonProto.Cmd.CMD_FRIEND_ACCEPT_RESP_VALUE, RelationProto.FriendAcceptResp.parser(), RelationProto.FriendAcceptResp.class);
    registerProto(CommonProto.Cmd.CMD_FRIEND_ACCEPT_NOTIFY_VALUE, RelationProto.FriendAcceptNotify.parser(), RelationProto.FriendAcceptNotify.class);
    registerProto(CommonProto.Cmd.CMD_FRIEND_DELETE_REQ_VALUE, RelationProto.FriendDeleteReq.parser(), RelationProto.FriendDeleteReq.class);
    registerProto(CommonProto.Cmd.CMD_FRIEND_DELETE_RESP_VALUE, RelationProto.FriendDeleteResp.parser(), RelationProto.FriendDeleteResp.class);
    registerProto(CommonProto.Cmd.CMD_FRIEND_DELETE_NOTIFY_VALUE, RelationProto.FriendDeleteNotify.parser(), RelationProto.FriendDeleteNotify.class);

    // 群管理
    registerProto(CommonProto.Cmd.CMD_GROUP_CREATE_REQ_VALUE, GroupMgmtProto.CreateGroupReq.parser(), GroupMgmtProto.CreateGroupReq.class);
    registerProto(CommonProto.Cmd.CMD_GROUP_CREATE_RESP_VALUE, GroupMgmtProto.CreateGroupResp.parser(), GroupMgmtProto.CreateGroupResp.class);
    registerProto(CommonProto.Cmd.CMD_GROUP_INVITE_REQ_VALUE, GroupMgmtProto.InviteToGroupReq.parser(), GroupMgmtProto.InviteToGroupReq.class);
    registerProto(CommonProto.Cmd.CMD_GROUP_INVITE_RESP_VALUE, GroupMgmtProto.InviteToGroupResp.parser(), GroupMgmtProto.InviteToGroupResp.class);
    registerProto(CommonProto.Cmd.CMD_GROUP_GET_INFO_REQ_VALUE, GroupMgmtProto.GetGroupInfoReq.parser(), GroupMgmtProto.GetGroupInfoReq.class);
    registerProto(CommonProto.Cmd.CMD_GROUP_GET_INFO_RESP_VALUE, GroupMgmtProto.GetGroupInfoResp.parser(), GroupMgmtProto.GetGroupInfoResp.class);
    registerProto(CommonProto.Cmd.CMD_GROUP_GET_MEMBERS_REQ_VALUE, GroupMgmtProto.GetGroupMembersReq.parser(), GroupMgmtProto.GetGroupMembersReq.class);
    registerProto(CommonProto.Cmd.CMD_GROUP_GET_MEMBERS_RESP_VALUE, GroupMgmtProto.GetGroupMembersResp.parser(), GroupMgmtProto.GetGroupMembersResp.class);
    registerProto(CommonProto.Cmd.CMD_GROUP_GET_MY_GROUPS_REQ_VALUE, GroupMgmtProto.GetMyGroupsReq.parser(), GroupMgmtProto.GetMyGroupsReq.class);
    registerProto(CommonProto.Cmd.CMD_GROUP_GET_MY_GROUPS_RESP_VALUE, GroupMgmtProto.GetMyGroupsResp.parser(), GroupMgmtProto.GetMyGroupsResp.class);
    registerProto(CommonProto.Cmd.CMD_GROUP_MEMBER_CHANGE_NOTIFY_VALUE, GroupMgmtProto.GroupMemberChangeNotify.parser(), GroupMgmtProto.GroupMemberChangeNotify.class);
    registerProto(CommonProto.Cmd.CMD_GROUP_PULL_MSG_REQ_VALUE, GroupMgmtProto.PullGroupMsgReq.parser(), GroupMgmtProto.PullGroupMsgReq.class);
    registerProto(CommonProto.Cmd.CMD_GROUP_PULL_MSG_RESP_VALUE, GroupMgmtProto.PullGroupMsgResp.parser(), GroupMgmtProto.PullGroupMsgResp.class);
    registerProto(CommonProto.Cmd.CMD_GROUP_ACK_REQ_VALUE, GroupMgmtProto.GroupAckReq.parser(), GroupMgmtProto.GroupAckReq.class);
    registerProto(CommonProto.Cmd.CMD_GROUP_ACK_RESP_VALUE, GroupMgmtProto.GroupAckResp.parser(), GroupMgmtProto.GroupAckResp.class);
  }

  /**
   * 注册 Parser 到注册表。cmdValue 在 0–255 范围用数组，超出用 overflow map。
   */
  private static <P extends Message> void registerProto(int cmdValue, Parser<P> parser, Class<P> messageType) {
    if (cmdValue >= 0 && cmdValue < 256) {
      PARSER_REGISTRY[cmdValue] = parser;
      PROTO_TYPE_REGISTRY[cmdValue] = messageType;
    } else {
      OVERFLOW_PARSERS.put(cmdValue, parser);
      OVERFLOW_TYPES.put(cmdValue, messageType);
    }
  }

  // ================================================================
  // 实例字段
  // ================================================================

  private final Parser<? extends Message> parser;
  /**
   * proto → DTO 映射函数；为 null 时表示 raw proto 模式
   */
  @SuppressWarnings("rawtypes")
  private final Function mapper;
  private final Class<T> resultType;

  // ================================================================
  // 构造器
  // ================================================================

  /**
   * Raw proto 模式（无 DTO 映射）。
   * decode 返回 proto Message，encode 输出 toByteArray()。
   */
  public <P extends Message> ProtobufCodec(Parser<P> parser, Class<P> protoType) {
    this.parser = parser;
    this.mapper = null;
    @SuppressWarnings("unchecked")
    Class<T> t = (Class<T>) protoType;
    this.resultType = t;
  }

  /**
   * DTO 映射模式。
   * decode 返回 DTO record，encode 不支持。
   */
  public <P extends Message> ProtobufCodec(Parser<P> parser, Function<P, T> mapper, Class<T> dtoType) {
    this.parser = parser;
    this.mapper = mapper;
    this.resultType = dtoType;
  }

  // ================================================================
  // 静态工厂
  // ================================================================

  /**
   * 根据 cmd 获取 raw proto 模式的 ProtobufCodec。
   */
  @SuppressWarnings("unchecked")
  public static <P extends Message> ProtobufCodec<P> getCodec(int cmd) {
    Parser<P> parser;
    Class<P> messageType;
    if (cmd >= 0 && cmd < 256) {
      parser = (Parser<P>) PARSER_REGISTRY[cmd];
      messageType = (Class<P>) PROTO_TYPE_REGISTRY[cmd];
    } else {
      parser = (Parser<P>) OVERFLOW_PARSERS.get(cmd);
      messageType = (Class<P>) OVERFLOW_TYPES.get(cmd);
    }
    if (parser == null || messageType == null) {
      throw new IllegalArgumentException("No parser registered for cmd: " + cmd);
    }
    return new ProtobufCodec<>(parser, messageType);
  }

  // ================================================================
  // MessageCodec 接口实现
  // ================================================================

  @Override
  @SuppressWarnings("unchecked")
  public T decode(byte[] data) {
    try {
      Message proto = parser.parseFrom(data);
      if (mapper != null) {
        return (T) mapper.apply(proto);
      }
      return (T) proto;
    } catch (Exception e) {
      throw new RuntimeException("Failed to decode protobuf message", e);
    }
  }

  @Override
  public byte[] encode(T message) {
    if (message instanceof Message msg) {
      return msg.toByteArray();
    }
    throw new UnsupportedOperationException(
      "ProtobufCodec.encode() is not supported for DTO mode; use encodeBody()/buildResponse()");
  }

  @Override
  public byte getCodecId() {
    return CODEC_ID;
  }

  @Override
  public Class<T> getMessageType() {
    return resultType;
  }

}
