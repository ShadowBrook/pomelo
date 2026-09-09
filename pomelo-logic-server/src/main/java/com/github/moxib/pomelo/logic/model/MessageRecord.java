package com.github.moxib.pomelo.logic.model;

/**
 * 消息持久化记录，对应 im_message_c2c 表。
 * senderId/recipientId 使用 im_user.id (BIGINT)。
 */
public class MessageRecord {

  private final long id;
  private final long senderId;
  private final long recipientId;
  // 会话ID (min_id:max_id, 如 "123:456")
  private final String conversationId;
  private final int msgType;
  private final String content;
  private final long seq;
  private final int status;
  private final long createdAt;
  // 客户端消息 ID（发送端生成，用于重试幂等去重）
  private final long clientMsgId;

  private MessageRecord(Builder builder) {
    this.id = builder.id;
    this.senderId = builder.senderId;
    this.recipientId = builder.recipientId;
    this.conversationId = builder.conversationId;
    this.msgType = builder.msgType;
    this.content = builder.content;
    this.seq = builder.seq;
    this.status = builder.status;
    this.createdAt = builder.createdAt;
    this.clientMsgId = builder.clientMsgId;
  }

  public static Builder builder() { return new Builder(); }

  /**
   * 计算会话 ID — min(id1, id2):max(id1, id2)。
   * 双向会话共用同一 ID，会话内排序使用 created_at。
   */
  public static String buildConversationId(long id1, long id2) {
    return id1 < id2 ? id1 + ":" + id2 : id2 + ":" + id1;
  }

  public long getId() { return id; }
  public long getSenderId() { return senderId; }
  public long getRecipientId() { return recipientId; }
  public String getConversationId() { return conversationId; }
  public int getMsgType() { return msgType; }
  public String getContent() { return content; }
  public long getSeq() { return seq; }
  public int getStatus() { return status; }
  public long getCreatedAt() { return createdAt; }
  public long getClientMsgId() { return clientMsgId; }

  public static class Builder {
    private long id;
    private long senderId;
    private long recipientId;
    private String conversationId;
    private int msgType;
    private String content;
    private long seq;
    private int status;
    private long createdAt;
    private long clientMsgId;

    public Builder id(long id) { this.id = id; return this; }
    public Builder senderId(long senderId) { this.senderId = senderId; return this; }
    public Builder recipientId(long recipientId) { this.recipientId = recipientId; return this; }
    public Builder conversationId(String conversationId) { this.conversationId = conversationId; return this; }
    public Builder msgType(int msgType) { this.msgType = msgType; return this; }
    public Builder content(String content) { this.content = content; return this; }
    public Builder seq(long seq) { this.seq = seq; return this; }
    public Builder status(int status) { this.status = status; return this; }
    public Builder createdAt(long createdAt) { this.createdAt = createdAt; return this; }
    public Builder clientMsgId(long clientMsgId) { this.clientMsgId = clientMsgId; return this; }

    public MessageRecord build() { return new MessageRecord(this); }
  }
}
