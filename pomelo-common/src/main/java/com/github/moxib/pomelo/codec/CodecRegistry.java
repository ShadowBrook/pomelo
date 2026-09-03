package com.github.moxib.pomelo.codec;

import com.google.protobuf.Message;
import com.google.protobuf.Parser;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 编解码器注册表。
 * 后端已全面切换到 Protobuf 单编解码（codecId 冻结为 0），此处仅注册/查询 Protobuf 编解码器。
 */
public class CodecRegistry {

    /**
     * 内部 Key: cmd#codecId
     * Protobuf 编解码器统一以 codecId=0 作为 key。
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
     * 获取编解码器（Protobuf-only）。
     * codecId 入参仅保留签名兼容（wire 上 codecId 已冻结为 0），查找时一律走 codecId=0，
     * 有 DTO 映射则用之，否则 fallback 到 ProtobufCodec 静态注册表。
     */
    @SuppressWarnings("unchecked")
    public <T> MessageCodec<T> getCodec(int cmd, int codecId) {
        MessageCodec<?> codec = codecs.get(buildKey(cmd, ProtobufCodec.CODEC_ID));
        if (codec != null) {
            return (MessageCodec<T>) codec;
        }
        // PB fallback：无 DTO 映射时返回原始 ProtobufCodec
        return (MessageCodec<T>) ProtobufCodec.getCodec(cmd);
    }

    /**
     * 根据 cmd 获取 Protobuf 编解码器（从静态注册表）
     */
    public <T extends Message> ProtobufCodec<T> getProtobufCodec(int cmd) {
        return ProtobufCodec.getCodec(cmd);
    }

    /**
     * 构建内部 Key
     */
    private String buildKey(int cmd, int codecId) {
        return cmd + "#" + codecId;
    }
}
