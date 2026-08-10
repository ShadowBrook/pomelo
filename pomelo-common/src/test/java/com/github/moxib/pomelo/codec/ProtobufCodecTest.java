package com.github.moxib.pomelo.codec;

import com.github.moxib.pomelo.proto.ack.AckProto;
import com.github.moxib.pomelo.proto.auth.AuthProto;
import com.github.moxib.pomelo.proto.chat.ChatProto;
import com.github.moxib.pomelo.proto.common.CommonProto;
import com.github.moxib.pomelo.proto.common.CommonProto.MsgType;
import com.github.moxib.pomelo.proto.ctrl.CtrlProto;
import com.github.moxib.pomelo.proto.group.GroupProto;
import com.github.moxib.pomelo.proto.heartbeat.HeartbeatProto;
import com.github.moxib.pomelo.proto.pull.PullProto;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ProtobufCodec 测试")
public class ProtobufCodecTest {

    @Test
    @DisplayName("测试 AuthReq 编解码")
    void testAuthReqEncodeDecode() {
        AuthProto.AuthReq original = AuthProto.AuthReq.newBuilder()
            .setToken("test-token-12345")
            .setDeviceId("device-001")
            .setPlatform("android")
            .setAppVersion("1.0.0")
            .build();

        ProtobufCodec<AuthProto.AuthReq> codec = ProtobufCodec.getCodec((byte) CommonProto.Cmd.CMD_AUTH_REQ_VALUE);

        byte[] encoded = codec.encode(original);
        assertNotNull(encoded);
        assertTrue(encoded.length > 0);

        AuthProto.AuthReq decoded = codec.decode(encoded);

        assertEquals(original.getToken(), decoded.getToken());
        assertEquals(original.getDeviceId(), decoded.getDeviceId());
        assertEquals(original.getPlatform(), decoded.getPlatform());
        assertEquals(original.getAppVersion(), decoded.getAppVersion());
    }

    @Test
    @DisplayName("测试 AuthResp 编解码")
    void testAuthRespEncodeDecode() {
        AuthProto.AuthResp original = AuthProto.AuthResp.newBuilder()
            .setCode(0)
            .setMessage("success")
            .setUserId("user-12345")
            .setExpireAt(System.currentTimeMillis() + 3600000)
            .build();

        ProtobufCodec<AuthProto.AuthResp> codec = ProtobufCodec.getCodec((byte) CommonProto.Cmd.CMD_AUTH_RESP_VALUE);

        byte[] encoded = codec.encode(original);
        AuthProto.AuthResp decoded = codec.decode(encoded);

        assertEquals(original.getCode(), decoded.getCode());
        assertEquals(original.getMessage(), decoded.getMessage());
        assertEquals(original.getUserId(), decoded.getUserId());
        assertEquals(original.getExpireAt(), decoded.getExpireAt());
    }

    @Test
    @DisplayName("测试 LogoutReq 编解码")
    void testLogoutReqEncodeDecode() {
        AuthProto.LogoutReq original = AuthProto.LogoutReq.newBuilder()
            .setReason("user logout")
            .build();

        ProtobufCodec<AuthProto.LogoutReq> codec = ProtobufCodec.getCodec((byte) CommonProto.Cmd.CMD_LOGOUT_REQ_VALUE);

        byte[] encoded = codec.encode(original);
        AuthProto.LogoutReq decoded = codec.decode(encoded);

        assertEquals(original.getReason(), decoded.getReason());
    }

    @Test
    @DisplayName("测试 LogoutResp 编解码")
    void testLogoutRespEncodeDecode() {
        AuthProto.LogoutResp original = AuthProto.LogoutResp.newBuilder()
            .setCode(0)
            .setMessage("logout success")
            .build();

        ProtobufCodec<AuthProto.LogoutResp> codec = ProtobufCodec.getCodec((byte) CommonProto.Cmd.CMD_LOGOUT_RESP_VALUE);

        byte[] encoded = codec.encode(original);
        AuthProto.LogoutResp decoded = codec.decode(encoded);

        assertEquals(original.getCode(), decoded.getCode());
        assertEquals(original.getMessage(), decoded.getMessage());
    }

    @Test
    @DisplayName("测试 C2CReq 编解码")
    void testC2CReqEncodeDecode() {
        CommonProto.MessageContent content = CommonProto.MessageContent.newBuilder()
            .setMsgType(MsgType.MSG_TYPE_TEXT)
            .setContent(ByteString.copyFrom("Hello, World!".getBytes(StandardCharsets.UTF_8)))
            .setTimestamp(System.currentTimeMillis())
            .build();

        ChatProto.C2CReq original = ChatProto.C2CReq.newBuilder()
            .setSenderId("user-001")
            .setRecipientId("user-002")
            .setMessage(content)
            .build();

        ProtobufCodec<ChatProto.C2CReq> codec = ProtobufCodec.getCodec((byte) CommonProto.Cmd.CMD_C2C_REQ_VALUE);

        byte[] encoded = codec.encode(original);
        ChatProto.C2CReq decoded = codec.decode(encoded);

        assertEquals(original.getSenderId(), decoded.getSenderId());
        assertEquals(original.getRecipientId(), decoded.getRecipientId());
        assertEquals(original.getMessage().getContent().toStringUtf8(), decoded.getMessage().getContent().toStringUtf8());
        assertEquals(original.getMessage().getMsgType(), decoded.getMessage().getMsgType());
    }

    @Test
    @DisplayName("测试 C2CResp 编解码")
    void testC2CRespEncodeDecode() {
        ChatProto.C2CResp original = ChatProto.C2CResp.newBuilder()
            .setCode(0)
            .setMessage("ok")
            .setMessageId(2001)
            .setServerTime(System.currentTimeMillis())
            .setSeq(100L)
            .build();

        ProtobufCodec<ChatProto.C2CResp> codec = ProtobufCodec.getCodec((byte) CommonProto.Cmd.CMD_C2C_RESP_VALUE);

        byte[] encoded = codec.encode(original);
        ChatProto.C2CResp decoded = codec.decode(encoded);

        assertEquals(original.getCode(), decoded.getCode());
        assertEquals(original.getMessage(), decoded.getMessage());
        assertEquals(original.getMessageId(), decoded.getMessageId());
        assertEquals(original.getServerTime(), decoded.getServerTime());
        assertEquals(original.getSeq(), decoded.getSeq());
    }

    @Test
    @DisplayName("测试 C2CNotify 编解码")
    void testC2CNotifyEncodeDecode() {
        CommonProto.MessageContent content = CommonProto.MessageContent.newBuilder()
            .setMsgType(MsgType.MSG_TYPE_TEXT)
            .setContent(ByteString.copyFrom("Test message with Chinese: 你好世界".getBytes(StandardCharsets.UTF_8)))
            .setTimestamp(System.currentTimeMillis())
            .build();

        ChatProto.C2CNotify original = ChatProto.C2CNotify.newBuilder()
            .setSenderId("user-001")
            .setRecipientId("user-002")
            .setMessage(content)
            .setSeq(3001L)
            .build();

        ProtobufCodec<ChatProto.C2CNotify> codec = ProtobufCodec.getCodec((byte) CommonProto.Cmd.CMD_C2C_NOTIFY_VALUE);

        byte[] encoded = codec.encode(original);
        ChatProto.C2CNotify decoded = codec.decode(encoded);

        assertEquals(original.getSenderId(), decoded.getSenderId());
        assertEquals(original.getRecipientId(), decoded.getRecipientId());
        assertEquals(original.getMessage().getContent().toStringUtf8(), decoded.getMessage().getContent().toStringUtf8());
        assertEquals(original.getMessage().getMsgType(), decoded.getMessage().getMsgType());
        assertEquals(original.getSeq(), decoded.getSeq());
    }

    @Test
    @DisplayName("测试 C2GReq 编解码")
    void testC2GReqEncodeDecode() {
        CommonProto.MessageContent content = CommonProto.MessageContent.newBuilder()
            .setMsgType(MsgType.MSG_TYPE_TEXT)
            .setContent(ByteString.copyFrom("Group message".getBytes(StandardCharsets.UTF_8)))
            .setTimestamp(System.currentTimeMillis())
            .build();

        GroupProto.C2GReq original = GroupProto.C2GReq.newBuilder()
            .setSenderId("user-001")
            .setGroupId("group-100")
            .setMessage(content)
            .build();

        ProtobufCodec<GroupProto.C2GReq> codec = ProtobufCodec.getCodec((byte) CommonProto.Cmd.CMD_C2G_REQ_VALUE);

        byte[] encoded = codec.encode(original);
        GroupProto.C2GReq decoded = codec.decode(encoded);

        assertEquals(original.getSenderId(), decoded.getSenderId());
        assertEquals(original.getGroupId(), decoded.getGroupId());
        assertEquals(original.getMessage().getContent().toStringUtf8(), decoded.getMessage().getContent().toStringUtf8());
    }

    @Test
    @DisplayName("测试 C2GResp 编解码")
    void testC2GRespEncodeDecode() {
        GroupProto.C2GResp original = GroupProto.C2GResp.newBuilder()
            .setCode(0)
            .setMessage("success")
            .setMessageId(5001)
            .setGroupId("group-100")
            .setServerTime(System.currentTimeMillis())
            .setSeq(100L)
            .build();

        ProtobufCodec<GroupProto.C2GResp> codec = ProtobufCodec.getCodec((byte) CommonProto.Cmd.CMD_C2G_RESP_VALUE);

        byte[] encoded = codec.encode(original);
        GroupProto.C2GResp decoded = codec.decode(encoded);

        assertEquals(original.getCode(), decoded.getCode());
        assertEquals(original.getMessage(), decoded.getMessage());
        assertEquals(original.getMessageId(), decoded.getMessageId());
        assertEquals(original.getGroupId(), decoded.getGroupId());
        assertEquals(original.getServerTime(), decoded.getServerTime());
    }

    @Test
    @DisplayName("测试 C2GNotify 编解码")
    void testC2GNotifyEncodeDecode() {
        CommonProto.MessageContent content = CommonProto.MessageContent.newBuilder()
            .setMsgType(MsgType.MSG_TYPE_TEXT)
            .setContent(ByteString.copyFrom("Group notification".getBytes(StandardCharsets.UTF_8)))
            .setTimestamp(System.currentTimeMillis())
            .build();

        GroupProto.C2GNotify original = GroupProto.C2GNotify.newBuilder()
            .setSenderId("user-001")
            .setGroupId("group-100")
            .setMessage(content)
            .setSeq(100L)
            .build();

        ProtobufCodec<GroupProto.C2GNotify> codec = ProtobufCodec.getCodec((byte) CommonProto.Cmd.CMD_C2G_NOTIFY_VALUE);

        byte[] encoded = codec.encode(original);
        GroupProto.C2GNotify decoded = codec.decode(encoded);

        assertEquals(original.getSenderId(), decoded.getSenderId());
        assertEquals(original.getGroupId(), decoded.getGroupId());
        assertEquals(original.getMessage().getContent().toStringUtf8(), decoded.getMessage().getContent().toStringUtf8());
        assertEquals(original.getSeq(), decoded.getSeq());
    }

    @Test
    @DisplayName("测试 PullReq 编解码")
    void testPullReqEncodeDecode() {
        PullProto.PullReq original = PullProto.PullReq.newBuilder()
            .setLimit(50)
            .setSeq(100L)
            .build();

        ProtobufCodec<PullProto.PullReq> codec = ProtobufCodec.getCodec((byte) CommonProto.Cmd.CMD_PULL_REQ_VALUE);

        byte[] encoded = codec.encode(original);
        PullProto.PullReq decoded = codec.decode(encoded);

        assertEquals(original.getLimit(), decoded.getLimit());
        assertEquals(original.getSeq(), decoded.getSeq());
    }

    @Test
    @DisplayName("测试 PullResp 编解码")
    void testPullRespEncodeDecode() {
        CommonProto.MessageContent msg1 = CommonProto.MessageContent.newBuilder()
            .setMsgType(MsgType.MSG_TYPE_TEXT)
            .setContent(ByteString.copyFrom("Message 1".getBytes(StandardCharsets.UTF_8)))
            .setTimestamp(System.currentTimeMillis())
            .build();

        CommonProto.MessageContent msg2 = CommonProto.MessageContent.newBuilder()
            .setMsgType(MsgType.MSG_TYPE_TEXT)
            .setContent(ByteString.copyFrom("Message 2".getBytes(StandardCharsets.UTF_8)))
            .setTimestamp(System.currentTimeMillis())
            .build();

        PullProto.PullResp original = PullProto.PullResp.newBuilder()
            .setCode(0)
            .setMessage("success")
            .addAllMessages(java.util.Arrays.asList(msg1, msg2))
            .setHasMore(false)
            .build();

        ProtobufCodec<PullProto.PullResp> codec = ProtobufCodec.getCodec((byte) CommonProto.Cmd.CMD_PULL_RESP_VALUE);

        byte[] encoded = codec.encode(original);
        PullProto.PullResp decoded = codec.decode(encoded);

        assertEquals(original.getCode(), decoded.getCode());
        assertEquals(original.getMessage(), decoded.getMessage());
        assertEquals(original.getMessagesCount(), decoded.getMessagesCount());
        assertEquals(original.getHasMore(), decoded.getHasMore());
    }

    @Test
    @DisplayName("测试 Ping 编解码")
    void testPingEncodeDecode() {
        HeartbeatProto.Ping original = HeartbeatProto.Ping.newBuilder()
            .setClientTime(System.currentTimeMillis())
            .build();

        ProtobufCodec<HeartbeatProto.Ping> codec = ProtobufCodec.getCodec((byte) CommonProto.Cmd.CMD_PING_VALUE);

        byte[] encoded = codec.encode(original);
        HeartbeatProto.Ping decoded = codec.decode(encoded);

        assertEquals(original.getClientTime(), decoded.getClientTime());
    }

    @Test
    @DisplayName("测试 Pong 编解码")
    void testPongEncodeDecode() {
        HeartbeatProto.Pong original = HeartbeatProto.Pong.newBuilder()
            .setServerTime(System.currentTimeMillis())
            .setClientTime(1234567890L)
            .build();

        ProtobufCodec<HeartbeatProto.Pong> codec = ProtobufCodec.getCodec((byte) CommonProto.Cmd.CMD_PONG_VALUE);

        byte[] encoded = codec.encode(original);
        HeartbeatProto.Pong decoded = codec.decode(encoded);

        assertEquals(original.getServerTime(), decoded.getServerTime());
        assertEquals(original.getClientTime(), decoded.getClientTime());
    }

    @Test
    @DisplayName("测试 AckReq 编解码")
    void testAckReqEncodeDecode() {
        AckProto.AckReq original = AckProto.AckReq.newBuilder()
            .addMessageIds(100)
            .addMessageIds(101)
            .addMessageIds(102)
            .setAckType(CommonProto.AckType.RECEIVED)
            .build();

        ProtobufCodec<AckProto.AckReq> codec = ProtobufCodec.getCodec((byte) CommonProto.Cmd.CMD_ACK_REQ_VALUE);

        byte[] encoded = codec.encode(original);
        AckProto.AckReq decoded = codec.decode(encoded);

        assertEquals(original.getAckType(), decoded.getAckType());
        assertEquals(original.getMessageIdsList(), decoded.getMessageIdsList());
    }

    @Test
    @DisplayName("测试 AckResp 编解码")
    void testAckRespEncodeDecode() {
        AckProto.AckResp original = AckProto.AckResp.newBuilder()
            .setAckType(CommonProto.AckType.RECEIVED)
            .build();

        ProtobufCodec<AckProto.AckResp> codec = ProtobufCodec.getCodec((byte) CommonProto.Cmd.CMD_ACK_RESP_VALUE);

        byte[] encoded = codec.encode(original);
        AckProto.AckResp decoded = codec.decode(encoded);

        assertEquals(original.getAckType(), decoded.getAckType());
    }

    @Test
    @DisplayName("测试 AckNotify 编解码")
    void testAckNotifyEncodeDecode() {
        AckProto.AckNotify original = AckProto.AckNotify.newBuilder()
            .addMessageIds(200)
            .addMessageIds(201)
            .addMessageIds(202)
            .build();

        ProtobufCodec<AckProto.AckNotify> codec = ProtobufCodec.getCodec((byte) CommonProto.Cmd.CMD_ACK_NOTIFY_VALUE);

        byte[] encoded = codec.encode(original);
        AckProto.AckNotify decoded = codec.decode(encoded);

        assertEquals(original.getMessageIdsList(), decoded.getMessageIdsList());
    }

    @Test
    @DisplayName("测试 CtrlReq 编解码")
    void testCtrlReqEncodeDecode() {
        CtrlProto.CtrlReq original = CtrlProto.CtrlReq.newBuilder()
            .setCtrlType(CtrlProto.CtrlType.CTRL_TYPE_NOTIFY)
            .setTargetUser("user-001")
            .setPayload(ByteString.copyFrom("test payload".getBytes(StandardCharsets.UTF_8)))
            .build();

        ProtobufCodec<CtrlProto.CtrlReq> codec = ProtobufCodec.getCodec((byte) CommonProto.Cmd.CMD_CTRL_REQ_VALUE);

        byte[] encoded = codec.encode(original);
        CtrlProto.CtrlReq decoded = codec.decode(encoded);

        assertEquals(original.getCtrlType(), decoded.getCtrlType());
        assertEquals(original.getTargetUser(), decoded.getTargetUser());
        assertEquals(original.getPayload().toStringUtf8(), decoded.getPayload().toStringUtf8());
    }

    @Test
    @DisplayName("测试 CtrlResp 编解码")
    void testCtrlRespEncodeDecode() {
        CtrlProto.CtrlResp original = CtrlProto.CtrlResp.newBuilder()
            .setCode(0)
            .setMessage("success")
            .setPayload(ByteString.copyFrom("response payload".getBytes(StandardCharsets.UTF_8)))
            .build();

        ProtobufCodec<CtrlProto.CtrlResp> codec = ProtobufCodec.getCodec((byte) CommonProto.Cmd.CMD_CTRL_RESP_VALUE);

        byte[] encoded = codec.encode(original);
        CtrlProto.CtrlResp decoded = codec.decode(encoded);

        assertEquals(original.getCode(), decoded.getCode());
        assertEquals(original.getMessage(), decoded.getMessage());
        assertEquals(original.getPayload().toStringUtf8(), decoded.getPayload().toStringUtf8());
    }

    @Test
    @DisplayName("测试 CtrlNotify 编解码")
    void testCtrlNotifyEncodeDecode() {
        CtrlProto.CtrlNotify original = CtrlProto.CtrlNotify.newBuilder()
            .setCtrlType(CtrlProto.CtrlType.CTRL_TYPE_KICK_OFFLINE)
            .setReason("kicked by admin")
            .setPayload(ByteString.copyFrom("notify payload".getBytes(StandardCharsets.UTF_8)))
            .build();

        ProtobufCodec<CtrlProto.CtrlNotify> codec = ProtobufCodec.getCodec((byte) CommonProto.Cmd.CMD_CTRL_NOTIFY_VALUE);

        byte[] encoded = codec.encode(original);
        CtrlProto.CtrlNotify decoded = codec.decode(encoded);

        assertEquals(original.getCtrlType(), decoded.getCtrlType());
        assertEquals(original.getReason(), decoded.getReason());
        assertEquals(original.getPayload().toStringUtf8(), decoded.getPayload().toStringUtf8());
    }

    @Test
    @DisplayName("测试无效 cmd 抛出异常")
    void testInvalidCmdThrowsException() {
        byte invalidCmd = (byte) 0xFF;
        assertThrows(IllegalArgumentException.class, () -> {
            ProtobufCodec.getCodec(invalidCmd);
        });
    }

    @Test
    @DisplayName("测试 getCodecId 返回正确的值")
    void testGetCodecId() {
        ProtobufCodec<AuthProto.AuthReq> codec = ProtobufCodec.getCodec((byte) CommonProto.Cmd.CMD_AUTH_REQ_VALUE);
        assertEquals((byte) 0, codec.getCodecId());
    }

    @Test
    @DisplayName("测试 getMessageType 返回正确的类型")
    void testGetMessageType() {
        ProtobufCodec<AuthProto.AuthReq> codec = ProtobufCodec.getCodec((byte) CommonProto.Cmd.CMD_AUTH_REQ_VALUE);
        assertEquals(AuthProto.AuthReq.class, codec.getMessageType());
    }

    @Test
    @DisplayName("测试损坏的 Protobuf 数据抛出异常")
    void testCorruptedDataThrowsException() {
        ProtobufCodec<AuthProto.AuthReq> codec = ProtobufCodec.getCodec((byte) CommonProto.Cmd.CMD_AUTH_REQ_VALUE);
        byte[] corruptedData = new byte[] { (byte) 0xFF, (byte) 0xFF, (byte) 0xFF };

        assertThrows(RuntimeException.class, () -> {
            codec.decode(corruptedData);
        });
    }

    @Test
    @DisplayName("测试包含二进制数据的消息编解码")
    void testMessageWithBinaryData() {
        byte[] binaryData = new byte[] { 0x01, 0x02, 0x03, 0x04, 0x05 };

        CommonProto.MessageContent content = CommonProto.MessageContent.newBuilder()
            .setMsgType(MsgType.MSG_TYPE_FILE)
            .setContent(ByteString.copyFrom(binaryData))
            .setTimestamp(System.currentTimeMillis())
            .build();

        ChatProto.C2CReq original = ChatProto.C2CReq.newBuilder()
            .setSenderId("user-001")
            .setRecipientId("user-002")
            .setMessage(content)
            .build();

        ProtobufCodec<ChatProto.C2CReq> codec = ProtobufCodec.getCodec((byte) CommonProto.Cmd.CMD_C2C_REQ_VALUE);

        byte[] encoded = codec.encode(original);
        ChatProto.C2CReq decoded = codec.decode(encoded);

        assertEquals(original.getSenderId(), decoded.getSenderId());
        assertEquals(original.getRecipientId(), decoded.getRecipientId());
        assertEquals(original.getMessage().getContent().toStringUtf8(), decoded.getMessage().getContent().toStringUtf8());
        assertEquals(original.getMessage().getMsgType(), decoded.getMessage().getMsgType());
    }

    @Test
    @DisplayName("测试包含扩展字段的消息编解码")
    void testMessageWithExtFields() {
        CommonProto.MessageContent content = CommonProto.MessageContent.newBuilder()
            .setMsgType(MsgType.MSG_TYPE_TEXT)
            .setContent(ByteString.copyFrom("Message with ext".getBytes(StandardCharsets.UTF_8)))
            .setTimestamp(System.currentTimeMillis())
            .putExt("key1", "value1")
            .putExt("key2", "value2")
            .build();

        ChatProto.C2CReq original = ChatProto.C2CReq.newBuilder()
            .setSenderId("user-001")
            .setRecipientId("user-002")
            .setMessage(content)
            .build();

        ProtobufCodec<ChatProto.C2CReq> codec = ProtobufCodec.getCodec((byte) CommonProto.Cmd.CMD_C2C_REQ_VALUE);

        byte[] encoded = codec.encode(original);
        ChatProto.C2CReq decoded = codec.decode(encoded);

        assertEquals(original.getSenderId(), decoded.getSenderId());
        assertEquals(original.getRecipientId(), decoded.getRecipientId());
        assertEquals(original.getMessage().getExtMap(), decoded.getMessage().getExtMap());
    }
}
