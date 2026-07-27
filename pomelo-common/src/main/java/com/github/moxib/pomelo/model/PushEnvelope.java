package com.github.moxib.pomelo.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Logic-Server → Gateway 的推送信封。
 * 在 EventBus 上以 JSON 编码传输，各 Gateway 节点收到后根据 targetUserId 查本地 SessionRegistry 投递。
 */
public class PushEnvelope {

  @JsonProperty("targetUserId")
  private String targetUserId;

  @JsonProperty("cmd")
  private int cmd;

  @JsonProperty("body")
  private byte[] body;

  @JsonProperty("codecId")
  private byte codecId;

  @JsonProperty("correlationMsgId")
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
