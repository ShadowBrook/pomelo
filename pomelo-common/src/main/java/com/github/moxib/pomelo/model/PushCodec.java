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
 *   | targetPlatformLen(2) | targetPlatform(var)（v3 追加字段；缺失读为 ""）
 *
 * 尾部追加字段一律按剩余长度判定解码，旧格式信封跨版本兼容。
 * 在 EventBus 上以 Buffer 传输，利用 Vert.x 内置 Buffer MessageCodec。
 * body 一律为 Protobuf 编码，不再携带 codecId 字节。
 */
public final class PushCodec {

  private PushCodec() {}

  public static Buffer encode(PushEnvelope env) {
    byte[] uid = env.getTargetUserId() != null
      ? env.getTargetUserId().getBytes(StandardCharsets.UTF_8) : new byte[0];
    byte[] body = env.getBody() != null ? env.getBody() : new byte[0];
    byte[] platform = env.getTargetPlatform().getBytes(StandardCharsets.UTF_8);

    Buffer buf = Buffer.buffer(2 + uid.length + 4 + 4 + body.length + 8 + 2 + platform.length);
    buf.appendUnsignedShort(uid.length);
    if (uid.length > 0) buf.appendBytes(uid);
    buf.appendInt(env.getCmd());
    buf.appendInt(body.length);
    if (body.length > 0) buf.appendBytes(body);
    buf.appendLong(env.getSentAtEpochMs());
    buf.appendUnsignedShort(platform.length);
    if (platform.length > 0) buf.appendBytes(platform);
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
    pos += bodyLen;

    PushEnvelope env = new PushEnvelope();
    env.setTargetUserId(targetUserId);
    env.setCmd(cmd);
    env.setBody(body);
    // 尾部追加字段：按剩余长度判定，旧格式（无 sentAt）兼容读为 0
    if (buf.length() - pos >= 8) {
      env.setSentAtEpochMs(buf.getLong(pos));
      pos += 8;
    }
    // v3 追加：targetPlatform（无该段读为 ""，投递退化为该用户全部端会话）
    if (buf.length() - pos >= 2) {
      int platformLen = buf.getUnsignedShort(pos); pos += 2;
      if (platformLen > 0 && buf.length() - pos >= platformLen) {
        env.setTargetPlatform(buf.getString(pos, pos + platformLen));
      }
    }
    return env;
  }
}
