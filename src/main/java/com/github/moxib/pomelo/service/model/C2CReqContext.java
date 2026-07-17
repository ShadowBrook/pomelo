package com.github.moxib.pomelo.service.model;

/**
 * C2C 发送请求上下文，由 Handler 解码后传入 MessageService。
 */
public class C2CReqContext {

  private final long messageId;       // 客户端雪花ID
  private final String senderId;
  private final String recipientId;
  private final int msgType;
  private final String content;
  private final long timestamp;       // 客户端时间戳（毫秒）

  private C2CReqContext(Builder builder) {
    this.messageId = builder.messageId;
    this.senderId = builder.senderId;
    this.recipientId = builder.recipientId;
    this.msgType = builder.msgType;
    this.content = builder.content;
    this.timestamp = builder.timestamp;
  }

  public static Builder builder() { return new Builder(); }

  public long getMessageId() { return messageId; }
  public String getSenderId() { return senderId; }
  public String getRecipientId() { return recipientId; }
  public int getMsgType() { return msgType; }
  public String getContent() { return content; }
  public long getTimestamp() { return timestamp; }

  public static class Builder {
    private long messageId;
    private String senderId;
    private String recipientId;
    private int msgType;
    private String content;
    private long timestamp;

    public Builder messageId(long messageId) { this.messageId = messageId; return this; }
    public Builder senderId(String senderId) { this.senderId = senderId; return this; }
    public Builder recipientId(String recipientId) { this.recipientId = recipientId; return this; }
    public Builder msgType(int msgType) { this.msgType = msgType; return this; }
    public Builder content(String content) { this.content = content; return this; }
    public Builder timestamp(long timestamp) { this.timestamp = timestamp; return this; }

    public C2CReqContext build() { return new C2CReqContext(this); }
  }
}
