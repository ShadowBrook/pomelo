package com.github.moxib.pomelo.model;

/**
 * Logic-Server → Gateway 的推送信封。
 * 在 EventBus 上以 {@link PushCodec} 二进制编码传输，各 Gateway 节点收到后
 * 根据 targetUserId 查本地 SessionRegistry 投递。
 *
 * body 为推送体字节，一律为 Protobuf 编码（codecId 冻结为 0），
 * Gateway 直接投递无需转换。
 */
public class PushEnvelope {

  private String targetUserId;
  private int cmd;
  private byte[] body;
  private String correlationMsgId;
  // logic 侧推送发起时刻（e2e 投递延迟观测用，见 docs/2026-09-17-im-metrics-plan.md）
  private long sentAtEpochMs;

  public PushEnvelope() {}

  public PushEnvelope(String targetUserId, int cmd, byte[] body) {
    this.targetUserId = targetUserId;
    this.cmd = cmd;
    this.body = body;
  }

  public String getTargetUserId() { return targetUserId; }
  public void setTargetUserId(String targetUserId) { this.targetUserId = targetUserId; }

  public int getCmd() { return cmd; }
  public void setCmd(int cmd) { this.cmd = cmd; }

  public byte[] getBody() { return body; }
  public void setBody(byte[] body) { this.body = body; }

  public String getCorrelationMsgId() { return correlationMsgId; }
  public void setCorrelationMsgId(String correlationMsgId) { this.correlationMsgId = correlationMsgId; }

  public long getSentAtEpochMs() { return sentAtEpochMs; }
  public void setSentAtEpochMs(long sentAtEpochMs) { this.sentAtEpochMs = sentAtEpochMs; }
}
