package com.github.moxib.pomelo.logic.model;

public class GroupMemberRecord {

  private final String groupId;
  private final long userId;
  private final String userName;
  private final String nickname;
  private final String avatar;
  private final int role;
  private final long joinedAt;

  private GroupMemberRecord(Builder builder) {
    this.groupId = builder.groupId;
    this.userId = builder.userId;
    this.userName = builder.userName;
    this.nickname = builder.nickname;
    this.avatar = builder.avatar;
    this.role = builder.role;
    this.joinedAt = builder.joinedAt;
  }

  public static Builder builder() { return new Builder(); }

  public String getGroupId() { return groupId; }
  public long getUserId() { return userId; }
  public String getUserName() { return userName; }
  public String getNickname() { return nickname; }
  public String getAvatar() { return avatar; }
  public int getRole() { return role; }
  public long getJoinedAt() { return joinedAt; }

  public static class Builder {
    private String groupId;
    private long userId;
    private String userName;
    private String nickname;
    private String avatar;
    private int role;
    private long joinedAt;

    public Builder groupId(String groupId) { this.groupId = groupId; return this; }
    public Builder userId(long userId) { this.userId = userId; return this; }
    public Builder userName(String userName) { this.userName = userName; return this; }
    public Builder nickname(String nickname) { this.nickname = nickname; return this; }
    public Builder avatar(String avatar) { this.avatar = avatar; return this; }
    public Builder role(int role) { this.role = role; return this; }
    public Builder joinedAt(long joinedAt) { this.joinedAt = joinedAt; return this; }

    public GroupMemberRecord build() { return new GroupMemberRecord(this); }
  }
}
