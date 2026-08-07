package com.github.moxib.pomelo.logic.model;

public class GroupMsgContext {

  private final long messageId;
  private final String groupId;
  private final String senderUserId;
  private final String senderUserName;
  private final String senderNickname;
  private final int msgType;
  private final String content;
  private final long timestamp;
  private final byte codecId;

  private GroupMsgContext(Builder builder) {
    this.messageId = builder.messageId;
    this.groupId = builder.groupId;
    this.senderUserId = builder.senderUserId;
    this.senderUserName = builder.senderUserName;
    this.senderNickname = builder.senderNickname;
    this.msgType = builder.msgType;
    this.content = builder.content;
    this.timestamp = builder.timestamp;
    this.codecId = builder.codecId;
  }

  public static Builder builder() { return new Builder(); }

  public long getMessageId() { return messageId; }
  public String getGroupId() { return groupId; }
  public String getSenderUserId() { return senderUserId; }
  public String getSenderUserName() { return senderUserName; }
  public String getSenderNickname() { return senderNickname; }
  public int getMsgType() { return msgType; }
  public String getContent() { return content; }
  public long getTimestamp() { return timestamp; }
  public byte getCodecId() { return codecId; }

  public static class Builder {
    private long messageId;
    private String groupId;
    private String senderUserId;
    private String senderUserName;
    private String senderNickname;
    private int msgType;
    private String content;
    private long timestamp;
    private byte codecId;

    public Builder messageId(long messageId) { this.messageId = messageId; return this; }
    public Builder groupId(String groupId) { this.groupId = groupId; return this; }
    public Builder senderUserId(String senderUserId) { this.senderUserId = senderUserId; return this; }
    public Builder senderUserName(String senderUserName) { this.senderUserName = senderUserName; return this; }
    public Builder senderNickname(String senderNickname) { this.senderNickname = senderNickname; return this; }
    public Builder msgType(int msgType) { this.msgType = msgType; return this; }
    public Builder content(String content) { this.content = content; return this; }
    public Builder timestamp(long timestamp) { this.timestamp = timestamp; return this; }
    public Builder codecId(byte codecId) { this.codecId = codecId; return this; }

    public GroupMsgContext build() { return new GroupMsgContext(this); }
  }
}
