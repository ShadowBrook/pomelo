package com.github.moxib.pomelo.logic.model;

public class GroupMsgContext {

  private final long messageId;
  private final long groupId;
  private final String groupName;
  private final long senderUserId;
  private final String senderUserName;
  private final String senderNickname;
  private final int msgType;
  private final String content;
  private final String ext;
  private final long timestamp;

  private GroupMsgContext(Builder builder) {
    this.messageId = builder.messageId;
    this.groupId = builder.groupId;
    this.groupName = builder.groupName;
    this.senderUserId = builder.senderUserId;
    this.senderUserName = builder.senderUserName;
    this.senderNickname = builder.senderNickname;
    this.msgType = builder.msgType;
    this.content = builder.content;
    this.ext = builder.ext;
    this.timestamp = builder.timestamp;
  }

  public static Builder builder() { return new Builder(); }

  public long getMessageId() { return messageId; }
  public long getGroupId() { return groupId; }
  public String getGroupName() { return groupName; }
  public long getSenderUserId() { return senderUserId; }
  public String getSenderUserName() { return senderUserName; }
  public String getSenderNickname() { return senderNickname; }
  public int getMsgType() { return msgType; }
  public String getContent() { return content; }
  /** 客户端扩展元数据 JSON（如 @ 提及），原样存储/推送；可为 null */
  public String getExt() { return ext; }
  public long getTimestamp() { return timestamp; }

  public static class Builder {
    private long messageId;
    private long groupId;
    private String groupName;
    private long senderUserId;
    private String senderUserName;
    private String senderNickname;
    private int msgType;
    private String content;
    private String ext;
    private long timestamp;

    public Builder messageId(long messageId) { this.messageId = messageId; return this; }
    public Builder groupId(long groupId) { this.groupId = groupId; return this; }
    public Builder groupName(String groupName) { this.groupName = groupName; return this; }
    public Builder senderUserId(long senderUserId) { this.senderUserId = senderUserId; return this; }
    public Builder senderUserName(String senderUserName) { this.senderUserName = senderUserName; return this; }
    public Builder senderNickname(String senderNickname) { this.senderNickname = senderNickname; return this; }
    public Builder msgType(int msgType) { this.msgType = msgType; return this; }
    public Builder content(String content) { this.content = content; return this; }
    public Builder ext(String ext) { this.ext = ext; return this; }
    public Builder timestamp(long timestamp) { this.timestamp = timestamp; return this; }

    public GroupMsgContext build() { return new GroupMsgContext(this); }
  }
}
