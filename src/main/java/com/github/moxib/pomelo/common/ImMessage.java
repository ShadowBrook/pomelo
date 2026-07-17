package com.github.moxib.pomelo.common;

import io.vertx.core.buffer.Buffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * +---------------+---------------+---------------+
 * |  FixedHeader  |   VarHeaders   |    MsgBody    |
 * |  固定头        |   变长头        |   消息体内容    |
 * +---------------+---------------+---------------+
 */
public class ImMessage {
  // 魔数
  private int magic;
  // 协议版本号
  private byte version;
  // 编码 Id
  private byte codecId;
  // IM 信令
  private int cmd;
  // 消息 ID
  private String messageId;
  // 变长头
  private Map<String, String> varHeaders;
  // 消息体长度
  private int bodyLength;
  // 消息体
  private byte[] body;
  // 协议版本常量
  public static final byte WIRE_PROTOCOL_VERSION = 1;
  // 魔数常量
  public static final int MAGIC_NUMBER = 0x504D454C; // "PMEL"

  /**
   * 编码到 Buffer
   * 协议格式:
   * - magic (4 bytes): 魔数
   * - version (1 byte): 协议版本号
   * - codecId (1 byte): 编码器 ID
   * - cmd (4 bytes): 命令
   * - messageIdLength (4 bytes): 消息 ID 长度
   * - messageId (variable): 消息 ID
   * - headersCount (4 bytes): 变长头数量
   * - headers (variable): 变长头 (每个头包含 keyLength, key, valueLength, value)
   * - bodyLength (4 bytes): 消息体长度
   * - body (variable): 消息体
   */
  public Buffer encodeToWire() {
    Buffer buffer = Buffer.buffer(1024);
    buffer.appendInt(0);
    // 魔数
    buffer.appendInt(MAGIC_NUMBER);
    // 协议版本号
    buffer.appendByte(version);
    // 编码 Id
    buffer.appendByte(codecId);
    // IM 信令
    buffer.appendInt(cmd);
    // 消息 ID
    writeString(buffer, messageId);
    // 变长头
    encodeHeaders(buffer);
    // 消息体长度
    int actualBodyLen = body != null ? body.length : bodyLength;
    buffer.appendInt(actualBodyLen);
    // 消息体
    if (body != null && actualBodyLen > 0) {
      buffer.appendBytes(body);
    }

    buffer.setInt(0, buffer.length() - 4);

    return buffer;
  }

  /**
   * 从 Buffer 解码
   * @param buffer 包含消息数据的 Buffer
   */
  public void readFromWire(Buffer buffer) {
    // Overall Length already read when passed in here
    int pos = 0;

    // 读取并验证魔数
    int magic = buffer.getInt(pos);
    if (magic != MAGIC_NUMBER) {
      throw new IllegalArgumentException("Invalid magic number: " + magic);
    }
    pos += 4;

    // 读取协议版本号
    this.version = buffer.getByte(pos);
    if (this.version > WIRE_PROTOCOL_VERSION) {
      throw new IllegalArgumentException("Unsupported protocol version: " + this.version);
    }
    pos += 1;

    // 读取编码 Id
    this.codecId = buffer.getByte(pos);
    pos += 1;

    // 读取 IM 信令 (int 占 4 字节)
    this.cmd = buffer.getInt(pos);
    pos += 4;

    // 读取消息 ID
    int messageIdLength = buffer.getInt(pos);
    pos += 4;
    if (messageIdLength > 0) {
      byte[] messageIdBytes = buffer.getBytes(pos, pos + messageIdLength);
      this.messageId = new String(messageIdBytes, StandardCharsets.UTF_8);
      pos += messageIdLength;
    }

    // 读取变长头
    pos = decodeHeaders(buffer, pos);

    // 读取消息体长度
    this.bodyLength = buffer.getInt(pos);
    pos += 4;

    // 读取消息体
    if (this.bodyLength > 0) {
      this.body = buffer.getBytes(pos, pos + bodyLength);
    }
  }

  private void writeString(Buffer buffer, String str) {
    if (str == null || str.isEmpty()) {
      buffer.appendInt(0);
    } else {
      byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
      buffer.appendInt(bytes.length);
      buffer.appendBytes(bytes);
    }
  }

  private void encodeHeaders(Buffer buffer) {
    if (varHeaders == null || varHeaders.isEmpty()) {
      buffer.appendInt(0);
    } else {
      buffer.appendInt(varHeaders.size());
      for (Map.Entry<String, String> entry : varHeaders.entrySet()) {
        writeString(buffer, entry.getKey());
        writeString(buffer, entry.getValue());
      }
    }
  }

  private int decodeHeaders(Buffer buffer, int startPos) {
    int pos = startPos;
    int headersCount = buffer.getInt(pos);
    pos += 4;

    if (headersCount > 0) {
      varHeaders = new java.util.HashMap<>();
      for (int i = 0; i < headersCount; i++) {
        int keyLength = buffer.getInt(pos);
        pos += 4;
        byte[] keyBytes = buffer.getBytes(pos, pos + keyLength);
        String key = new String(keyBytes, StandardCharsets.UTF_8);
        pos += keyLength;

        int valueLength = buffer.getInt(pos);
        pos += 4;
        byte[] valueBytes = buffer.getBytes(pos, pos + valueLength);
        String value = new String(valueBytes, StandardCharsets.UTF_8);
        pos += valueLength;

        varHeaders.put(key, value);
      }
    }
    return pos;
  }

  public ImMessage() {}

  /**
   * 公共构造函数，允许外部代码创建实例
   */
  public ImMessage(boolean dummy) {
    // 用于测试和外部代码创建实例
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private int magic;
    private byte version;
    private byte codecId;
    private int cmd;
    private String messageId;
    private int bodyLength;
    private byte[] body;
    private Map<String, String> varHeaders;

    public Builder magic(int magic) {
      this.magic = magic;
      return this;
    }

    public Builder version(byte version) {
      this.version = version;
      return this;
    }

    public Builder codecId(byte codecId) {
      this.codecId = codecId;
      return this;
    }

    public Builder cmd(int cmd) {
      this.cmd = cmd;
      return this;
    }

    public Builder messageId(String messageId) {
      this.messageId = messageId;
      return this;
    }

    public Builder bodyLength(int bodyLength) {
      this.bodyLength = bodyLength;
      return this;
    }

    public Builder body(byte[] body) {
      this.body = body;
      return this;
    }

    public Builder varHeaders(Map<String, String> varHeaders) {
      this.varHeaders = varHeaders;
      return this;
    }

    public ImMessage build() {
      ImMessage message = new ImMessage();
      message.magic = this.magic;
      message.version = this.version;
      message.codecId = this.codecId;
      message.cmd = this.cmd;
      message.messageId = this.messageId;
      message.bodyLength = this.bodyLength;
      message.body = this.body;
      message.varHeaders = this.varHeaders;
      return message;
    }
  }

  // Getters and Setters
  public int getMagic() {
    return magic;
  }

  public void setMagic(int magic) {
    this.magic = magic;
  }

  public byte getVersion() {
    return version;
  }

  public void setVersion(byte version) {
    this.version = version;
  }

  public byte getCodecId() {
    return codecId;
  }

  public void setCodecId(byte codecId) {
    this.codecId = codecId;
  }

  public int getCmd() {
    return cmd;
  }

  public void setCmd(int cmd) {
    this.cmd = cmd;
  }

  public String getMessageId() {
    return messageId;
  }

  public void setMessageId(String messageId) {
    this.messageId = messageId;
  }

  public int getBodyLength() {
    return bodyLength;
  }

  public void setBodyLength(int bodyLength) {
    this.bodyLength = bodyLength;
  }

  public byte[] getBody() {
    return body;
  }

  public void setBody(byte[] body) {
    this.body = body;
  }

  public Map<String, String> getVarHeaders() {
    return varHeaders;
  }

  public void setVarHeaders(Map<String, String> varHeaders) {
    this.varHeaders = varHeaders;
  }
}
