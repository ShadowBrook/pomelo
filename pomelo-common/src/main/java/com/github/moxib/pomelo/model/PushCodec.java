package com.github.moxib.pomelo.model;

import io.vertx.core.buffer.Buffer;

import java.nio.charset.StandardCharsets;

/**
 * Push 消息二进制编解码 — 替代 PushEnvelope JSON 序列化。
 *
 * 格式（大端）:
 *   targetUserIdLen(2) | targetUserId(var) | cmd(4) | codecId(1)
 *   | pbBodyLen(4) | pbBody(var) | jsonBodyLen(4) | jsonBody(var)
 *
 * 在 EventBus 上以 Buffer 传输，利用 Vert.x 内置 Buffer MessageCodec。
 */
public final class PushCodec {

  private PushCodec() {}

  public static Buffer encode(PushEnvelope env) {
    byte[] uid = env.getTargetUserId() != null
      ? env.getTargetUserId().getBytes(StandardCharsets.UTF_8) : new byte[0];
    byte[] pb = env.getBody() != null ? env.getBody() : new byte[0];
    byte[] json = env.getJsonBody() != null ? env.getJsonBody() : new byte[0];

    Buffer buf = Buffer.buffer(2 + uid.length + 4 + 1 + 4 + pb.length + 4 + json.length);
    buf.appendUnsignedShort(uid.length);
    if (uid.length > 0) buf.appendBytes(uid);
    buf.appendInt(env.getCmd());
    buf.appendByte(env.getCodecId());
    buf.appendInt(pb.length);
    if (pb.length > 0) buf.appendBytes(pb);
    buf.appendInt(json.length);
    if (json.length > 0) buf.appendBytes(json);
    return buf;
  }

  public static PushEnvelope decode(Buffer buf) {
    int pos = 0;
    int uidLen = buf.getUnsignedShort(pos); pos += 2;
    String targetUserId = uidLen > 0 ? buf.getString(pos, pos + uidLen) : null;
    pos += uidLen;
    int cmd = buf.getInt(pos); pos += 4;
    byte codecId = buf.getByte(pos); pos += 1;
    int pbLen = buf.getInt(pos); pos += 4;
    byte[] pbBody = pbLen > 0 ? buf.getBytes(pos, pos + pbLen) : null;
    pos += pbLen;
    int jsonLen = buf.getInt(pos); pos += 4;
    byte[] jsonBody = jsonLen > 0 ? buf.getBytes(pos, pos + jsonLen) : null;

    PushEnvelope env = new PushEnvelope();
    env.setTargetUserId(targetUserId);
    env.setCmd(cmd);
    env.setCodecId(codecId);
    env.setBody(pbBody);
    env.setJsonBody(jsonBody);
    return env;
  }
}
