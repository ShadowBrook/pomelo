package com.github.moxib.pomelo.codec;

/**
 * 消息编解码器接口
 * 用于将业务消息对象与 byte[] 之间进行转换
 */
public interface MessageCodec<T> {

    /**
     * 编码：将业务消息对象编码为字节数组
     * @param message 业务消息对象
     * @return 编码后的字节数组
     */
    byte[] encode(T message);

    /**
     * 解码：将字节数组解码为业务消息对象
     * @param data 字节数组
     * @return 业务消息对象
     */
    T decode(byte[] data);

    /**
     * 获取支持的编码 ID
     * @return 编码 ID (0=protobuf, 1=json)
     */
    byte getCodecId();

    /**
     * 获取支持的消息类型
     * @return 消息类型 Class
     */
    Class<T> getMessageType();
}
