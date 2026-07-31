package com.github.moxib.pomelo.codec;

import com.google.protobuf.Message;
import com.google.protobuf.Parser;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 编解码器注册表。
 * 通过 cmd + codecId 路由到具体的编解码器。
 * PB 路径（codecId=0）自动从 ProtobufCodec 静态注册表 fallback。
 */
public class CodecRegistry {

    /**
     * 内部 Key: cmd#codecId
     * 例如：1#1 表示 cmd=1, codecId=1(JSON)
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
     * 注册 Protobuf → DTO 编解码器
     */
    public <P extends Message, T> void registerProtobuf(int cmd, Parser<P> parser,
                                                         Function<P, T> mapper, Class<T> dtoType) {
        register(cmd, new ProtobufCodec<>(parser, mapper, dtoType));
    }

    /**
     * 获取编解码器。
     * codecId == 0 时优先查注册表（有 DTO 映射则用），否则 fallback 到 ProtobufCodec 静态注册表。
     * codecId == 1 时从注册表查 JSON 编解码器。
     */
    @SuppressWarnings("unchecked")
    public <T> MessageCodec<T> getCodec(int cmd, int codecId) {
        String key = buildKey(cmd, codecId);
        MessageCodec<?> codec = codecs.get(key);
        if (codec != null) {
            return (MessageCodec<T>) codec;
        }
        // PB fallback：无 DTO 映射时返回原始 ProtobufCodec
        if (codecId == ProtobufCodec.CODEC_ID) {
            return (MessageCodec<T>) ProtobufCodec.getCodec(cmd);
        }
        return null;
    }

    /**
     * 根据 cmd 获取 Protobuf 编解码器（从静态注册表）
     */
    public <T extends Message> ProtobufCodec<T> getProtobufCodec(int cmd) {
        return ProtobufCodec.getCodec(cmd);
    }

    /**
     * 注册 JSON 编解码器
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
