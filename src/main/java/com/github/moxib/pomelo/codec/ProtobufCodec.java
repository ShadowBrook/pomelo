package com.github.moxib.pomelo.codec;

import com.github.moxib.pomelo.proto.ack.AckProto;
import com.github.moxib.pomelo.proto.auth.AuthProto;
import com.github.moxib.pomelo.proto.chat.ChatProto;
import com.github.moxib.pomelo.proto.common.CommonProto;
import com.github.moxib.pomelo.proto.ctrl.CtrlProto;
import com.github.moxib.pomelo.proto.group.GroupProto;
import com.github.moxib.pomelo.proto.heartbeat.HeartbeatProto;
import com.github.moxib.pomelo.proto.pull.PullProto;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;

/**
 * Protobuf 编解码器实现
 * 针对 com.github.moxib.pomelo.proto 包中的类，使用静态映射表避免反射,所以新生成的proto类需要注册进来
 */
public class ProtobufCodec<T extends Message> implements MessageCodec<T> {

    private static final byte CODEC_ID = 0;

    /**
     * Cmd 到 Parser 的映射表 (cmd 值范围 0-255)
     */
    private static final Parser<?>[] PARSER_REGISTRY = new Parser[256];

    /**
     * Cmd 到 Message 类型的映射表
     */
    @SuppressWarnings("unchecked")
    private static final Class<? extends Message>[] MESSAGE_TYPE_REGISTRY = new Class[256];

    static {
        // 认证相关 (cmd: 1-4)
        register(CommonProto.Cmd.CMD_AUTH_REQ_VALUE, AuthProto.AuthReq.parser(), AuthProto.AuthReq.class);
        register(CommonProto.Cmd.CMD_AUTH_RESP_VALUE, AuthProto.AuthResp.parser(), AuthProto.AuthResp.class);
        register(CommonProto.Cmd.CMD_LOGOUT_REQ_VALUE, AuthProto.LogoutReq.parser(), AuthProto.LogoutReq.class);
        register(CommonProto.Cmd.CMD_LOGOUT_RESP_VALUE, AuthProto.LogoutResp.parser(), AuthProto.LogoutResp.class);

        // 单聊相关 (cmd: 16-18)
        register(CommonProto.Cmd.CMD_C2C_REQ_VALUE, ChatProto.C2CReq.parser(), ChatProto.C2CReq.class);
        register(CommonProto.Cmd.CMD_C2C_RESP_VALUE, ChatProto.C2CResp.parser(), ChatProto.C2CResp.class);
        register(CommonProto.Cmd.CMD_C2C_NOTIFY_VALUE, ChatProto.C2CNotify.parser(), ChatProto.C2CNotify.class);

        // 群聊相关 (cmd: 32-34)
        register(CommonProto.Cmd.CMD_C2G_REQ_VALUE, GroupProto.C2GReq.parser(), GroupProto.C2GReq.class);
        register(CommonProto.Cmd.CMD_C2G_RESP_VALUE, GroupProto.C2GResp.parser(), GroupProto.C2GResp.class);
        register(CommonProto.Cmd.CMD_C2G_NOTIFY_VALUE, GroupProto.C2GNotify.parser(), GroupProto.C2GNotify.class);

        // 消息拉取 (cmd: 48-49)
        register(CommonProto.Cmd.CMD_PULL_REQ_VALUE, PullProto.PullReq.parser(), PullProto.PullReq.class);
        register(CommonProto.Cmd.CMD_PULL_RESP_VALUE, PullProto.PullResp.parser(), PullProto.PullResp.class);

        // 控制命令 (cmd: 64-66)
        register(CommonProto.Cmd.CMD_CTRL_REQ_VALUE, CtrlProto.CtrlReq.parser(), CtrlProto.CtrlReq.class);
        register(CommonProto.Cmd.CMD_CTRL_RESP_VALUE, CtrlProto.CtrlResp.parser(), CtrlProto.CtrlResp.class);
        register(CommonProto.Cmd.CMD_CTRL_NOTIFY_VALUE, CtrlProto.CtrlNotify.parser(), CtrlProto.CtrlNotify.class);

        // 心跳与 ACK (cmd: 80-84)
        register(CommonProto.Cmd.CMD_PING_VALUE, HeartbeatProto.Ping.parser(), HeartbeatProto.Ping.class);
        register(CommonProto.Cmd.CMD_PONG_VALUE, HeartbeatProto.Pong.parser(), HeartbeatProto.Pong.class);
        register(CommonProto.Cmd.CMD_ACK_REQ_VALUE, AckProto.AckReq.parser(), AckProto.AckReq.class);
        register(CommonProto.Cmd.CMD_ACK_RESP_VALUE, AckProto.AckResp.parser(), AckProto.AckResp.class);
        register(CommonProto.Cmd.CMD_ACK_NOTIFY_VALUE, AckProto.AckNotify.parser(), AckProto.AckNotify.class);
    }

    private final Class<T> messageType;
    private final Parser<T> parser;

    /**
     * 根据 cmd 获取 ProtobufCodec 实例
     */
    @SuppressWarnings("unchecked")
    public static <T extends Message> ProtobufCodec<T> getCodec(int cmd) {
        Parser<T> parser = (Parser<T>) PARSER_REGISTRY[cmd];
        Class<T> messageType = (Class<T>) MESSAGE_TYPE_REGISTRY[cmd];
        if (parser == null || messageType == null) {
            throw new IllegalArgumentException("No parser registered for cmd: " + cmd);
        }
        return new ProtobufCodec<>(messageType, parser);
    }

    /**
     * 注册 Parser 到静态映射表
     */
    private static <T extends Message> void register(int cmdValue, Parser<T> parser, Class<T> messageType) {
        PARSER_REGISTRY[cmdValue] = parser;
        MESSAGE_TYPE_REGISTRY[cmdValue] = messageType;
    }

    private ProtobufCodec(Class<T> messageType, Parser<T> parser) {
        this.messageType = messageType;
        this.parser = parser;
    }

    @Override
    public byte[] encode(T message) {
        return message.toByteArray();
    }

    @Override
    public T decode(byte[] data) {
        try {
            return parser.parseFrom(data);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decode protobuf message", e);
        }
    }

    @Override
    public byte getCodecId() {
        return CODEC_ID;
    }

    @Override
    public Class<T> getMessageType() {
        return messageType;
    }
}
