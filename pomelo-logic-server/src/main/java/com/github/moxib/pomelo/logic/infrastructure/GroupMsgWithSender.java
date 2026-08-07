package com.github.moxib.pomelo.logic.infrastructure;

/**
 * 群消息查询结果 — 消息内容 + 发送者 numeric id。
 * senderNumericId 后续通过 MessageRepository.findUserIdsByIds 转换为显示名。
 */
public class GroupMsgWithSender {

  private final long id;
  private final long senderNumericId;
  private final String groupId;
  private final int msgType;
  private final String content;
  private final long seq;
  private final long createdAt;

  public GroupMsgWithSender(long id, long senderNumericId, String groupId, int msgType,
                             String content, long seq, long createdAt) {
    this.id = id;
    this.senderNumericId = senderNumericId;
    this.groupId = groupId;
    this.msgType = msgType;
    this.content = content;
    this.seq = seq;
    this.createdAt = createdAt;
  }

  public long getId() { return id; }
  public long getSenderNumericId() { return senderNumericId; }
  public String getGroupId() { return groupId; }
  public int getMsgType() { return msgType; }
  public String getContent() { return content; }
  public long getSeq() { return seq; }
  public long getCreatedAt() { return createdAt; }
}
