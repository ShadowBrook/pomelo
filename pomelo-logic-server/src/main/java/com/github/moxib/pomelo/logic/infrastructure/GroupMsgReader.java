package com.github.moxib.pomelo.logic.infrastructure;

/**
 * 群消息已读用户信息。
 */
public class GroupMsgReader {

  private final String userId;
  private final String nickname;
  private final String avatar;

  public GroupMsgReader(String userId, String nickname, String avatar) {
    this.userId = userId;
    this.nickname = nickname;
    this.avatar = avatar;
  }

  public String getUserId() { return userId; }
  public String getNickname() { return nickname; }
  public String getAvatar() { return avatar; }
}
