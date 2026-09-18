package com.github.moxib.pomelo.model;

import io.vertx.core.buffer.Buffer;

import java.nio.charset.StandardCharsets;

/**
 * Push 消息二进制编解码。
 *
 * 格式（大端）:
 *   targetUserIdLen(2) | targetUserId(var) | cmd(4)
 *   | bodyLen(4) | body(var)
 *   | sentAtEpochMs(8)（v2 追加字段；解码按剩余长度兼容旧格式，缺失记 0）
 *
 * 在 EventBus 上以 Buffer 传输，利用 Vert.x 内置 Buffer MessageCodec。
 * body 一律为 Protobuf 编码，不再携带 codecId 字节。
 */
public final class PushCodec {

  private PushCodec() {}

  public static Buffer encode(PushEnvelope env) {
    byte[] uid = env.getTargetUserId() != null
      ? env.getTargetUserId().getBytes(StandardCharsets.UTF_8) : new byte[0];
    byte[] body = env.getBody() != null ? env.getBody() : new byte[0];

    Buffer buf = Buffer.buffer(2 + uid.length + 4 + 4 + body.length + 8);
    buf.appendUnsignedShort(uid.length);
    if (uid.length > 0) buf.appendBytes(uid);
    buf.appendInt(env.getCmd());
    buf.appendInt(body.length);
    if (body.length > 0) buf.appendBytes(body);
    buf.appendLong(env.getSentAtEpochMs());
    return buf;
  }

  public static PushEnvelope decode(Buffer buf) {
    int pos = 0;
    int uidLen = buf.getUnsignedShort(pos); pos += 2;
    String targetUserId = uidLen > 0 ? buf.getString(pos, pos + uidLen) : null;
    pos += uidLen;
    int cmd = buf.getInt(pos); pos += 4;
    int bodyLen = buf.getInt(pos); pos += 4;
    byte[] body = bodyLen > 0 ? buf.getBytes(pos, pos + bodyLen) : null;

    PushEnvelope env = new PushEnvelope();
    env.setTargetUserId(targetUserId);
    env.setCmd(cmd);
    env.setBody(body);
    // 尾部追加字段：按剩余长度判定，旧格式（无 sentAt）兼容读为 0
    if (buf.length() - pos >= 8) {
      env.setSentAtEpochMs(buf.getLong(pos));
    }
    return env;
  }
}
