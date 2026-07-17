package com.github.moxib.pomelo.service.model;

import java.util.Collections;
import java.util.List;

/**
 * ACK 通知上下文，由 processAck 返回，Handler 用它构建 AckNotify 推送给发送者。
 */
public class AckNotifyContext {

  private final String senderId;
  private final List<Long> messageIds;
  private final int ackType;   // 0=RECEIVED, 1=SEEN

  public AckNotifyContext(String senderId, List<Long> messageIds, int ackType) {
    this.senderId = senderId;
    this.messageIds = Collections.unmodifiableList(messageIds);
    this.ackType = ackType;
  }

  public String getSenderId() { return senderId; }
  public List<Long> getMessageIds() { return messageIds; }
  public int getAckType() { return ackType; }
}
