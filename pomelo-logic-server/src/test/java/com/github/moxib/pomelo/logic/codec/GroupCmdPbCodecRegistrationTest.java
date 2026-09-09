package com.github.moxib.pomelo.logic.codec;

import com.github.moxib.pomelo.codec.MessageCodec;
import com.github.moxib.pomelo.logic.model.requests.C2GRequest;
import com.github.moxib.pomelo.logic.model.requests.CreateGroupRequest;
import com.github.moxib.pomelo.logic.model.requests.GetGroupInfoRequest;
import com.github.moxib.pomelo.logic.model.requests.GetGroupMembersRequest;
import com.github.moxib.pomelo.logic.model.requests.GroupAckRequest;
import com.github.moxib.pomelo.logic.model.requests.GroupMsgReadRequest;
import com.github.moxib.pomelo.logic.model.requests.GroupPullMsgRequest;
import com.github.moxib.pomelo.logic.model.requests.InviteToGroupRequest;
import com.github.moxib.pomelo.proto.common.CommonProto;
import com.github.moxib.pomelo.proto.group.GroupMgmtProto;
import com.github.moxib.pomelo.proto.group.GroupProto;
import com.github.moxib.pomelo.proto.pull.PullProto;
import com.google.protobuf.Message;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 群聊/群管理命令的 PB 请求 codec + DTO mapper 注册测试（纯单元，无外部依赖）。 */
class GroupCmdPbCodecRegistrationTest {

  private Object decode(int cmd, Message proto) {
    MessageCodec<?> codec = CodecRegistryHolder.REGISTRY.getCodec(cmd, 0);
    assertNotNull(codec, "cmd 0x" + Integer.toHexString(cmd) + " 应有 PB codec");
    return codec.decode(proto.toByteArray());
  }

  @Test
  void c2gReqMapsDto() {
    GroupProto.C2GReq proto = GroupProto.C2GReq.newBuilder()
      .setSenderId(1L).setGroupId(7L).setMessageId(9L)
      .setMessage(CommonProto.MessageContent.newBuilder()
        .setMsgTypeValue(1).setContent(ByteString.copyFromUtf8("hi")).build())
      .build();
    C2GRequest req = (C2GRequest) decode(CommonProto.Cmd.CMD_C2G_REQ_VALUE, proto);
    assertEquals("7", req.groupId());
    assertEquals(9L, req.messageId());
    assertEquals(1, req.message().msgType());
    assertEquals("hi", req.message().content());
  }

  @Test
  void groupPullReqMapsDto() {
    PullProto.PullGroupMsgReq proto = PullProto.PullGroupMsgReq.newBuilder()
      .setGroupId(7L).setCursor(100L).setLimit(30).setIsBackward(true).build();
    GroupPullMsgRequest req = (GroupPullMsgRequest) decode(CommonProto.Cmd.CMD_GROUP_PULL_MSG_REQ_VALUE, proto);
    assertEquals("7", req.groupId());
    assertEquals(100L, req.cursor());
    assertEquals(30, req.limit());
    assertEquals(true, req.isBackward());
  }

  @Test
  void groupAckReqMapsDto() {
    GroupMgmtProto.GroupAckReq proto = GroupMgmtProto.GroupAckReq.newBuilder()
      .setGroupId(7L).setLastReadSeq(42L).build();
    GroupAckRequest req = (GroupAckRequest) decode(CommonProto.Cmd.CMD_GROUP_ACK_REQ_VALUE, proto);
    assertEquals("7", req.groupId());
    assertEquals(42L, req.lastReadSeq());
  }

  @Test
  void createGroupReqMapsDto() {
    GroupMgmtProto.CreateGroupReq proto = GroupMgmtProto.CreateGroupReq.newBuilder()
      .setName("组").setAvatar("a.png").build();
    CreateGroupRequest req = (CreateGroupRequest) decode(CommonProto.Cmd.CMD_GROUP_CREATE_REQ_VALUE, proto);
    assertEquals("组", req.name());
    assertEquals("a.png", req.avatar());
  }

  @Test
  void inviteReqMapsDto() {
    GroupMgmtProto.InviteToGroupReq proto = GroupMgmtProto.InviteToGroupReq.newBuilder()
      .setGroupId(7L).setUserId(9L).build();
    InviteToGroupRequest req = (InviteToGroupRequest) decode(CommonProto.Cmd.CMD_GROUP_INVITE_REQ_VALUE, proto);
    assertEquals("7", req.groupId());
    assertEquals("9", req.userId());
  }

  @Test
  void getInfoReqMapsDto() {
    GroupMgmtProto.GetGroupInfoReq proto = GroupMgmtProto.GetGroupInfoReq.newBuilder().setGroupId(7L).build();
    GetGroupInfoRequest req = (GetGroupInfoRequest) decode(CommonProto.Cmd.CMD_GROUP_GET_INFO_REQ_VALUE, proto);
    assertEquals("7", req.groupId());
  }

  @Test
  void getMembersReqMapsDto() {
    GroupMgmtProto.GetGroupMembersReq proto = GroupMgmtProto.GetGroupMembersReq.newBuilder().setGroupId(7L).build();
    GetGroupMembersRequest req = (GetGroupMembersRequest) decode(CommonProto.Cmd.CMD_GROUP_GET_MEMBERS_REQ_VALUE, proto);
    assertEquals("7", req.groupId());
  }

  @Test
  void msgReadReqMapsDto() {
    GroupMgmtProto.GetGroupMsgReadStatusReq proto = GroupMgmtProto.GetGroupMsgReadStatusReq.newBuilder()
      .setGroupId(7L).setSeq(5L).build();
    GroupMsgReadRequest req = (GroupMsgReadRequest) decode(CommonProto.Cmd.CMD_GROUP_MSG_READ_REQ_VALUE, proto);
    assertEquals("7", req.groupId());
    assertEquals(5L, req.seq());
  }
}
