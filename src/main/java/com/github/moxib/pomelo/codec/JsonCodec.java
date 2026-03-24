package com.github.moxib.pomelo.codec;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON 编解码器实现
 * 支持任意 POJO 类型
 */
public class JsonCodec<T> implements MessageCodec<T> {

    private static final byte CODEC_ID = 1;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Class<T> messageType;

    public JsonCodec(Class<T> messageType) {
        this.messageType = messageType;
    }

    @Override
    public byte[] encode(T message) {
        try {
            return MAPPER.writeValueAsBytes(message);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode JSON message", e);
        }
    }

    @Override
    public T decode(byte[] data) {
        try {
            return MAPPER.readValue(data, messageType);
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
