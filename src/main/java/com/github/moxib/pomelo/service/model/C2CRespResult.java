package com.github.moxib.pomelo.service.model;

/**
 * C2C 发送结果，由 MessageService 返回给 Handler 构建响应。
 */
public class C2CRespResult {

  private final int code;
  private final String message;
  private final long messageId;
  private final long seq;
  private final long serverTime;

  private C2CRespResult(Builder builder) {
    this.code = builder.code;
    this.message = builder.message;
    this.messageId = builder.messageId;
    this.seq = builder.seq;
    this.serverTime = builder.serverTime;
  }

  public static Builder builder() { return new Builder(); }

  public int getCode() { return code; }
  public String getMessage() { return message; }
  public long getMessageId() { return messageId; }
  public long getSeq() { return seq; }
  public long getServerTime() { return serverTime; }

  public static class Builder {
    private int code;
    private String message;
    private long messageId;
    private long seq;
    private long serverTime;

    public Builder code(int code) { this.code = code; return this; }
    public Builder message(String message) { this.message = message; return this; }
    public Builder messageId(long messageId) { this.messageId = messageId; return this; }
    public Builder seq(long seq) { this.seq = seq; return this; }
    public Builder serverTime(long serverTime) { this.serverTime = serverTime; return this; }

    public C2CRespResult build() { return new C2CRespResult(this); }
  }
}
