package com.github.moxib.pomelo.service.model;

/**
 * C2C 发送请求上下文，由 Handler 解码后传入 MessageService。
 * senderId/recipientId 使用 im_user.id (BIGINT)。
 */
public class C2CReqContext {

  // 客户端雪花ID
  private final long messageId;
  private final long senderId;
  private final long recipientId;
  // 发送方 userId (NanoID)
  private final String senderUserId;
  // 发送方 userName
  private final String senderUserName;
  // 发送方 nickname
  private final String senderNickname;
  private final int msgType;
  private final String content;
  // 客户端时间戳（毫秒）
  private final long timestamp;

  private C2CReqContext(Builder builder) {
    this.messageId = builder.messageId;
    this.senderId = builder.senderId;
    this.recipientId = builder.recipientId;
    this.senderUserId = builder.senderUserId;
    this.senderUserName = builder.senderUserName;
    this.senderNickname = builder.senderNickname;
    this.msgType = builder.msgType;
    this.content = builder.content;
    this.timestamp = builder.timestamp;
  }

  public static Builder builder() { return new Builder(); }

  public long getMessageId() { return messageId; }
  public long getSenderId() { return senderId; }
  public long getRecipientId() { return recipientId; }
  public String getSenderUserId() { return senderUserId; }
  public String getSenderUserName() { return senderUserName; }
  public String getSenderNickname() { return senderNickname; }
  public int getMsgType() { return msgType; }
  public String getContent() { return content; }
  public long getTimestamp() { return timestamp; }

  public static class Builder {
    private long messageId;
    private long senderId;
    private long recipientId;
    private String senderUserId;
    private String senderUserName;
    private String senderNickname;
    private int msgType;
    private String content;
    private long timestamp;

    public Builder messageId(long messageId) { this.messageId = messageId; return this; }
    public Builder senderId(long senderId) { this.senderId = senderId; return this; }
    public Builder recipientId(long recipientId) { this.recipientId = recipientId; return this; }
    public Builder senderUserId(String senderUserId) { this.senderUserId = senderUserId; return this; }
    public Builder senderUserName(String senderUserName) { this.senderUserName = senderUserName; return this; }
    public Builder senderNickname(String senderNickname) { this.senderNickname = senderNickname; return this; }
    public Builder msgType(int msgType) { this.msgType = msgType; return this; }
    public Builder content(String content) { this.content = content; return this; }
    public Builder timestamp(long timestamp) { this.timestamp = timestamp; return this; }

    public C2CReqContext build() { return new C2CReqContext(this); }
  }
}
