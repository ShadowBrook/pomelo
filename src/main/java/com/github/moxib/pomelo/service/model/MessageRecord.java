package com.github.moxib.pomelo.service.model;

/**
 * 消息持久化记录，对应 im_message_c2c 表。
 */
public class MessageRecord {

  private final long id;          // 客户端雪花ID
  private final String senderId;
  private final String recipientId;
  private final int msgType;
  private final String content;
  private final long seq;         // 服务端全局序列号
  private final int status;       // 0=已发送, 1=已送达, 2=已读
  private final long createdAt;

  private MessageRecord(Builder builder) {
    this.id = builder.id;
    this.senderId = builder.senderId;
    this.recipientId = builder.recipientId;
    this.msgType = builder.msgType;
    this.content = builder.content;
    this.seq = builder.seq;
    this.status = builder.status;
    this.createdAt = builder.createdAt;
  }

  public static Builder builder() {
    return new Builder();
  }

  // ---- getters ----
  public long getId() { return id; }
  public String getSenderId() { return senderId; }
  public String getRecipientId() { return recipientId; }
  public int getMsgType() { return msgType; }
  public String getContent() { return content; }
  public long getSeq() { return seq; }
  public int getStatus() { return status; }
  public long getCreatedAt() { return createdAt; }

  public static class Builder {
    private long id;
    private String senderId;
    private String recipientId;
    private int msgType;
    private String content;
    private long seq;
    private int status;
    private long createdAt;

    public Builder id(long id) { this.id = id; return this; }
    public Builder senderId(String senderId) { this.senderId = senderId; return this; }
    public Builder recipientId(String recipientId) { this.recipientId = recipientId; return this; }
    public Builder msgType(int msgType) { this.msgType = msgType; return this; }
    public Builder content(String content) { this.content = content; return this; }
    public Builder seq(long seq) { this.seq = seq; return this; }
    public Builder status(int status) { this.status = status; return this; }
    public Builder createdAt(long createdAt) { this.createdAt = createdAt; return this; }

    public MessageRecord build() { return new MessageRecord(this); }
  }
}
