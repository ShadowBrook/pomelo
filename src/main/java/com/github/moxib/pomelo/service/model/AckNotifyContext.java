package com.github.moxib.pomelo.service.model;

import java.util.Collections;
import java.util.List;

/**
 * ACK 通知上下文，由 processAck 返回，Handler 用它构建 AckNotify 推送给发送者。
 */
public class AckNotifyContext {

  // im_user.id
  private final long senderId;
  // 发送方 userId (NanoID, 协议层标识, 用于推送)
  private final String senderUserId;
  private final List<Long> messageIds;
  // 0=RECEIVED, 1=SEEN
  private final int ackType;

  public AckNotifyContext(long senderId, String senderUserId, List<Long> messageIds, int ackType) {
    this.senderId = senderId;
    this.senderUserId = senderUserId;
    this.messageIds = Collections.unmodifiableList(messageIds);
    this.ackType = ackType;
  }

  public long getSenderId() { return senderId; }
  public String getSenderUserId() { return senderUserId; }
  public List<Long> getMessageIds() { return messageIds; }
  public int getAckType() { return ackType; }
}
