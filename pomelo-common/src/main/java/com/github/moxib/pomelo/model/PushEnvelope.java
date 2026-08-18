package com.github.moxib.pomelo.model;

/**
 * Logic-Server → Gateway 的推送信封。
 * 在 EventBus 上以 JSON 编码传输，各 Gateway 节点收到后根据 targetUserId 查本地 SessionRegistry 投递。
 *
 * body 为 PB 编码的推送体，jsonBody 为等价的 JSON 编码体（可选）。
 * Gateway 根据接收方的 codec 选择用哪个 body：
 *   - codecId=0 (PB) → 用 body
 *   - codecId=1 (JSON) → 用 jsonBody（如果非 null 且非空）
 */
public class PushEnvelope {

  private String targetUserId;
  private int cmd;
  private byte[] body;
  private byte[] jsonBody;
  private byte codecId;
  private String correlationMsgId;

  public PushEnvelope() {}

  public PushEnvelope(String targetUserId, int cmd, byte[] body, byte codecId) {
    this.targetUserId = targetUserId;
    this.cmd = cmd;
    this.body = body;
    this.codecId = codecId;
  }

  public PushEnvelope(String targetUserId, int cmd, byte[] body, byte[] jsonBody, byte codecId) {
    this.targetUserId = targetUserId;
    this.cmd = cmd;
    this.body = body;
    this.jsonBody = jsonBody;
    this.codecId = codecId;
  }

  public String getTargetUserId() { return targetUserId; }
  public void setTargetUserId(String targetUserId) { this.targetUserId = targetUserId; }

  public int getCmd() { return cmd; }
  public void setCmd(int cmd) { this.cmd = cmd; }

  public byte[] getBody() { return body; }
  public void setBody(byte[] body) { this.body = body; }

  public byte[] getJsonBody() { return jsonBody; }
  public void setJsonBody(byte[] jsonBody) { this.jsonBody = jsonBody; }

  public byte getCodecId() { return codecId; }
  public void setCodecId(byte codecId) { this.codecId = codecId; }

  public String getCorrelationMsgId() { return correlationMsgId; }
  public void setCorrelationMsgId(String correlationMsgId) { this.correlationMsgId = correlationMsgId; }
}
