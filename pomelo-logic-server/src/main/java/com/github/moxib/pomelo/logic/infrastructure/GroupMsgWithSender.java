package com.github.moxib.pomelo.logic.infrastructure;

/**
 * 群消息查询结果 — 消息内容 + 发送者 numeric id + 群 ID。
 * senderNumericId 后续通过 MessageRepository.findUserIdsByIds 转换为显示名。
 */
public class GroupMsgWithSender {

  private final long id;
  private final long senderNumericId;
  private final long groupId;
  private final int msgType;
  private final String content;
  private final String ext;
  private final long seq;
  private final long createdAt;

  public GroupMsgWithSender(long id, long senderNumericId, long groupId, int msgType,
                             String content, String ext, long seq, long createdAt) {
    this.id = id;
    this.senderNumericId = senderNumericId;
    this.groupId = groupId;
    this.msgType = msgType;
    this.content = content;
    this.ext = ext;
    this.seq = seq;
    this.createdAt = createdAt;
  }

  public long getId() { return id; }
  public long getSenderNumericId() { return senderNumericId; }
  public long getGroupId() { return groupId; }
  public int getMsgType() { return msgType; }
  public String getContent() { return content; }
  public String getExt() { return ext; }
  public long getSeq() { return seq; }
  public long getCreatedAt() { return createdAt; }
}
