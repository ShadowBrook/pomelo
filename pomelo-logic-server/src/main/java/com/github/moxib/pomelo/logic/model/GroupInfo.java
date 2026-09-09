package com.github.moxib.pomelo.logic.model;

public class GroupInfo {

  private final long id;
  private final String name;
  private final String avatar;
  private final String description;
  private final long ownerId;
  private final int memberCount;
  private final int maxMembers;
  private final long createdAt;
  private final long updatedAt;

  private GroupInfo(Builder builder) {
    this.id = builder.id;
    this.name = builder.name;
    this.avatar = builder.avatar;
    this.description = builder.description;
    this.ownerId = builder.ownerId;
    this.memberCount = builder.memberCount;
    this.maxMembers = builder.maxMembers;
    this.createdAt = builder.createdAt;
    this.updatedAt = builder.updatedAt;
  }

  public static Builder builder() { return new Builder(); }

  public long getId() { return id; }
  public String getName() { return name; }
  public String getAvatar() { return avatar; }
  public String getDescription() { return description; }
  public long getOwnerId() { return ownerId; }
  public int getMemberCount() { return memberCount; }
  public int getMaxMembers() { return maxMembers; }
  public long getCreatedAt() { return createdAt; }
  public long getUpdatedAt() { return updatedAt; }

  public static class Builder {
    private long id;
    private String name;
    private String avatar;
    private String description;
    private long ownerId;
    private int memberCount;
    private int maxMembers;
    private long createdAt;
    private long updatedAt;

    public Builder id(long id) { this.id = id; return this; }
    public Builder name(String name) { this.name = name; return this; }
    public Builder avatar(String avatar) { this.avatar = avatar; return this; }
    public Builder description(String description) { this.description = description; return this; }
    public Builder ownerId(long ownerId) { this.ownerId = ownerId; return this; }
    public Builder memberCount(int memberCount) { this.memberCount = memberCount; return this; }
    public Builder maxMembers(int maxMembers) { this.maxMembers = maxMembers; return this; }
    public Builder createdAt(long createdAt) { this.createdAt = createdAt; return this; }
    public Builder updatedAt(long updatedAt) { this.updatedAt = updatedAt; return this; }

    public GroupInfo build() { return new GroupInfo(this); }
  }
}
