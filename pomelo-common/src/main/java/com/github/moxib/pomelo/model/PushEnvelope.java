package com.github.moxib.pomelo.model;

/**
 * Logic-Server → Gateway 的推送信封。
 * 在 EventBus 上以 {@link PushCodec} 二进制编码传输，各 Gateway 节点收到后
 * 根据 targetUserId 查本地 SessionRegistry 投递。
 *
 * body 为推送体字节（PB 或 JSON），codecId 标识其编码格式。
 * Gateway 直接按 codecId 投递，不做格式转换。
 */
public class PushEnvelope {

  private String targetUserId;
  private int cmd;
  private byte[] body;
  private byte codecId;
  private String correlationMsgId;

  public PushEnvelope() {}

  public PushEnvelope(String targetUserId, int cmd, byte[] body, byte codecId) {
    this.targetUserId = targetUserId;
    this.cmd = cmd;
    this.body = body;
    this.codecId = codecId;
  }

  public String getTargetUserId() { return targetUserId; }
  public void setTargetUserId(String targetUserId) { this.targetUserId = targetUserId; }

  public int getCmd() { return cmd; }
  public void setCmd(int cmd) { this.cmd = cmd; }

  public byte[] getBody() { return body; }
  public void setBody(byte[] body) { this.body = body; }

  public byte getCodecId() { return codecId; }
  public void setCodecId(byte codecId) { this.codecId = codecId; }

  public String getCorrelationMsgId() { return correlationMsgId; }
  public void setCorrelationMsgId(String correlationMsgId) { this.correlationMsgId = correlationMsgId; }
}
