package com.github.moxib.pomelo.codec;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.Json;

/**
 * JSON 编解码器实现
 * 支持任意 POJO 类型
 */
public class JsonCodec<T> implements MessageCodec<T> {

    private static final byte CODEC_ID = 1;
    private final Class<T> messageType;

    public JsonCodec(Class<T> messageType) {
        this.messageType = messageType;
    }

    @Override
    public byte[] encode(T message) {
        try {
            return Json.encodeToBuffer(message).getBytes();
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode JSON message", e);
        }
    }

    @Override
    public T decode(byte[] data) {
        try {
            return Json.decodeValue(Buffer.buffer(data), messageType);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decode JSON message", e);
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
