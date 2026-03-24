package com.github.moxib.pomelo.codec;

import com.google.protobuf.Message;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 编解码器注册表
 * 通过 cmd + codecId 路由到具体的编解码器
 */
public class CodecRegistry {

    /**
     * 内部 Key: cmd#codecId
     * 例如：1#0 表示 cmd=1, codecId=0(protobuf)
     */
    private final Map<String, MessageCodec<?>> codecs = new ConcurrentHashMap<>();

    /**
     * 注册编解码器
     * @param cmd 命令字
     * @param codec 编解码器实例
     */
    public void register(int cmd, MessageCodec<?> codec) {
        String key = buildKey(cmd, codec.getCodecId());
        codecs.put(key, codec);
    }

    /**
     * 获取编解码器
     * @param cmd 命令字
     * @param codecId 编码 ID
     * @return 编解码器实例
     */
    @SuppressWarnings("unchecked")
    public <T> MessageCodec<T> getCodec(int cmd, int codecId) {
        String key = buildKey(cmd, codecId);
        return (MessageCodec<T>) codecs.get(key);
    }

    /**
     * 根据 cmd 获取 Protobuf 编解码器（从静态注册表）
     * 该方法直接使用 ProtobufCodec 内部的静态映射表，无需预先注册
     * @param cmd 命令字
     * @return Protobuf 编解码器实例
     */
    public <T extends Message> ProtobufCodec<T> getProtobufCodec(int cmd) {
        return ProtobufCodec.getCodec(cmd);
    }

    /**
     * 注册 JSON 编解码器
     * @param cmd 命令字
     * @param messageType 消息类型
     */
    public void registerJson(int cmd, Class<?> messageType) {
        register(cmd, new JsonCodec<>(messageType));
    }

    /**
     * 构建内部 Key
     */
    private String buildKey(int cmd, int codecId) {
        return cmd + "#" + codecId;
    }
}
