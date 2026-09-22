package com.github.moxib.pomelo.model;

import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PushCodec 编解码测试：v3（携带 targetPlatform 尾部字段）往返，
 * 以及对 v1（无 sentAt）/v2（无 platform）旧格式信封的兼容解码。
 */
@DisplayName("PushCodec 编解码测试")
class PushCodecTest {

  @Test
  @DisplayName("v3 往返：userId/cmd/body/sentAt/platform 全量还原")
  void roundTripV3() {
    PushEnvelope env = new PushEnvelope("user-1", 0x0012, new byte[]{4, 5, 6});
    env.setTargetPlatform("web");
    env.setCorrelationMsgId("corr-1");
    env.setSentAtEpochMs(1727000000000L);

    PushEnvelope decoded = PushCodec.decode(PushCodec.encode(env));

    assertEquals("user-1", decoded.getTargetUserId());
    assertEquals(0x0012, decoded.getCmd());
    assertArrayEquals(new byte[]{4, 5, 6}, decoded.getBody());
    assertEquals(1727000000000L, decoded.getSentAtEpochMs());
    assertEquals("web", decoded.getTargetPlatform());
    // correlationMsgId 不上线（信封内没有该字段），保持既有行为
    assertNull(decoded.getCorrelationMsgId());
  }

  @Test
  @DisplayName("v3：未设置 platform 时编码为空串，解码回空串")
  void emptyPlatformRoundTrip() {
    PushEnvelope env = new PushEnvelope("user-2", 0x0042, new byte[]{1});

    PushEnvelope decoded = PushCodec.decode(PushCodec.encode(env));

    assertEquals("", decoded.getTargetPlatform());
    assertEquals("user-2", decoded.getTargetUserId());
  }

  @Test
  @DisplayName("兼容 v2 旧格式（无 platform 段）：platform 解码为空串")
  void decodeLegacyV2() {
    // 手工拼 v2 格式：uidLen|uid|cmd|bodyLen|body|sentAt
    byte[] uid = "user-3".getBytes(StandardCharsets.UTF_8);
    byte[] body = new byte[]{9, 8};
    Buffer legacy = Buffer.buffer();
    legacy.appendUnsignedShort(uid.length).appendBytes(uid);
    legacy.appendInt(0x0012);
    legacy.appendInt(body.length).appendBytes(body);
    legacy.appendLong(1727000000000L);

    PushEnvelope decoded = PushCodec.decode(legacy);

    assertEquals("user-3", decoded.getTargetUserId());
    assertEquals(0x0012, decoded.getCmd());
    assertArrayEquals(body, decoded.getBody());
    assertEquals(1727000000000L, decoded.getSentAtEpochMs());
    assertEquals("", decoded.getTargetPlatform(), "v2 信封无端型，应读为空串");
  }

  @Test
  @DisplayName("兼容 v1 旧格式（无 sentAt/platform 段）：sentAt=0、platform 空串")
  void decodeLegacyV1() {
    byte[] uid = "user-4".getBytes(StandardCharsets.UTF_8);
    byte[] body = new byte[]{7};
    Buffer legacy = Buffer.buffer();
    legacy.appendUnsignedShort(uid.length).appendBytes(uid);
    legacy.appendInt(0x0030);
    legacy.appendInt(body.length).appendBytes(body);

    PushEnvelope decoded = PushCodec.decode(legacy);

    assertEquals("user-4", decoded.getTargetUserId());
    assertEquals(0x0030, decoded.getCmd());
    assertArrayEquals(body, decoded.getBody());
    assertEquals(0L, decoded.getSentAtEpochMs(), "v1 无 sentAt，应读为 0");
    assertEquals("", decoded.getTargetPlatform());
  }
}
