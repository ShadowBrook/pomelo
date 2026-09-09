package com.github.moxib.pomelo.common;

import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ImMessage 编解码测试")
public class ImMessageTest {

  @Test
  @DisplayName("测试完整的编解码流程 - 包含所有字段")
  void testEncodeDecodeWithAllFields() {
    // 准备测试数据
    Map<String, String> headers = new HashMap<>();
    headers.put("userId", "12345");
    headers.put("roomId", "room-001");

    ImMessage original = ImMessage.builder()
        .version((byte) 1)
        .codecId((byte) 2)
        .cmd((byte) 100)
        .messageId("msg-uuid-001")
        .bodyLength(11)
        .body("hello world".getBytes(StandardCharsets.UTF_8))
        .varHeaders(headers)
        .build();

    // 编码
    Buffer buffer = original.encodeToWire();
    assertNotNull(buffer);
    assertTrue(buffer.length() > 0);

    // 解码
    ImMessage decoded = new ImMessage();
    decoded.readFromWire(buffer.getBuffer(4, buffer.length()));

    // 验证所有字段
    assertEquals(original.getVersion(), decoded.getVersion());
    assertEquals(original.getCodecId(), decoded.getCodecId());
    assertEquals(original.getCmd(), decoded.getCmd());
    assertEquals(original.getMessageId(), decoded.getMessageId());
    assertEquals(original.getBodyLength(), decoded.getBodyLength());
    assertArrayEquals(original.getBody(), decoded.getBody());
    assertEquals(original.getVarHeaders(), decoded.getVarHeaders());
  }

  @Test
  @DisplayName("测试编解码流程 - 无变长头")
  void testEncodeDecodeWithoutHeaders() {
    ImMessage original = ImMessage.builder()
        .version((byte) 1)
        .codecId((byte) 1)
        .cmd((byte) 50)
        .messageId("msg-no-headers")
        .bodyLength(5)
        .body("test!".getBytes(StandardCharsets.UTF_8))
        .build();

    Buffer buffer = original.encodeToWire();
    ImMessage decoded = new ImMessage();
    decoded.readFromWire(buffer.getBuffer(4, buffer.length()));

    assertEquals(original.getVersion(), decoded.getVersion());
    assertEquals(original.getCodecId(), decoded.getCodecId());
    assertEquals(original.getCmd(), decoded.getCmd());
    assertEquals(original.getMessageId(), decoded.getMessageId());
    assertEquals(original.getBodyLength(), decoded.getBodyLength());
    assertArrayEquals(original.getBody(), decoded.getBody());
    assertNull(decoded.getVarHeaders());
  }

  @Test
  @DisplayName("测试编解码流程 - 空消息体")
  void testEncodeDecodeWithEmptyBody() {
    ImMessage original = ImMessage.builder()
        .version((byte) 1)
        .codecId((byte) 3)
        .cmd((byte) 200)
        .messageId("msg-empty-body")
        .bodyLength(0)
        .body(new byte[0])
        .build();

    Buffer buffer = original.encodeToWire();
    ImMessage decoded = new ImMessage();
    decoded.readFromWire(buffer.getBuffer(4, buffer.length()));

    assertEquals(original.getVersion(), decoded.getVersion());
    assertEquals(original.getCodecId(), decoded.getCodecId());
    assertEquals(original.getCmd(), decoded.getCmd());
    assertEquals(original.getMessageId(), decoded.getMessageId());
    assertEquals(0, decoded.getBodyLength());
    assertNull(decoded.getBody());
  }

  @Test
  @DisplayName("测试编解码流程 - 中文消息体")
  void testEncodeDecodeWithChineseBody() {
    String chineseText = "你好，世界！";
    ImMessage original = ImMessage.builder()
        .version((byte) 1)
        .codecId((byte) 1)
        .cmd((byte) 10)
        .messageId("msg-chinese")
        .bodyLength(chineseText.getBytes(StandardCharsets.UTF_8).length)
        .body(chineseText.getBytes(StandardCharsets.UTF_8))
        .build();

    Buffer buffer = original.encodeToWire();
    ImMessage decoded = new ImMessage();
    decoded.readFromWire(buffer.getBuffer(4, buffer.length()));

    assertArrayEquals(original.getBody(), decoded.getBody());
    assertEquals(chineseText, new String(decoded.getBody(), StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("测试魔数验证 - 无效魔数抛出异常")
  void testInvalidMagicNumber() {
    Buffer buffer = Buffer.buffer(1024);
    // 错误的魔数
    buffer.appendInt(0xDEADBEEF);
    buffer.appendByte((byte) 1);
    buffer.appendByte((byte) 1);
    buffer.appendByte((byte) 1);
    // messageId length
    buffer.appendInt(0);
    // headers count
    buffer.appendInt(0);
    // body length
    buffer.appendInt(0);

    ImMessage message = new ImMessage();
    assertThrows(IllegalArgumentException.class, () -> {
      message.readFromWire(buffer);
    });
  }

  @Test
  @DisplayName("测试协议版本验证 - 不支持的版本号抛出异常")
  void testUnsupportedProtocolVersion() {
    ImMessage original = ImMessage.builder()
        // 不支持的版本号
        .version((byte) 99)
        .codecId((byte) 1)
        .cmd((byte) 1)
        .messageId("msg-test")
        .build();

    Buffer buffer = original.encodeToWire();
    ImMessage decoded = new ImMessage();

    assertThrows(IllegalArgumentException.class, () -> {
      decoded.readFromWire(buffer.getBuffer(4, buffer.length()));
    });
  }

  @Test
  @DisplayName("测试多个变长头的编解码")
  void testEncodeDecodeWithMultipleHeaders() {
    Map<String, String> headers = new HashMap<>();
    headers.put("header1", "value1");
    headers.put("header2", "value2");
    headers.put("header3", "value3");
    headers.put("token", "jwt-token-12345");

    ImMessage original = ImMessage.builder()
        .magic(ImMessage.MAGIC_NUMBER)
        .version((byte) 1)
        .codecId((byte) 5)
        .cmd((byte) 99)
        .messageId("msg-multi-headers")
        .bodyLength(4)
        .body("data".getBytes(StandardCharsets.UTF_8))
        .varHeaders(headers)
        .build();

    Buffer buffer = original.encodeToWire();
    ImMessage decoded = new ImMessage();
    decoded.readFromWire(buffer.getBuffer(4, buffer.length()));

    assertEquals(headers.size(), decoded.getVarHeaders().size());
    assertEquals(headers, decoded.getVarHeaders());
  }

  @Test
  @DisplayName("测试 null 消息 ID 的编解码")
  void testEncodeDecodeWithNullMessageId() {
    ImMessage original = ImMessage.builder()
        .version((byte) 1)
        .codecId((byte) 1)
        .cmd((byte) 1)
        .messageId(null)
        .build();

    Buffer buffer = original.encodeToWire();
    ImMessage decoded = new ImMessage();
    decoded.readFromWire(buffer.getBuffer(4, buffer.length()));

    assertNull(decoded.getMessageId());
  }

  @Test
  @DisplayName("测试帧过短 - 抛出异常")
  void testFrameTooShort() {
    Buffer buffer = Buffer.buffer();
    buffer.appendInt(ImMessage.MAGIC_NUMBER);
    buffer.appendByte((byte) 1);
    buffer.appendByte((byte) 0);

    ImMessage message = new ImMessage();
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> message.readFromWire(buffer));
    assertTrue(e.getMessage().contains("too short"));
  }

  @Test
  @DisplayName("测试负数 messageId 长度 - 抛出异常")
  void testNegativeMessageIdLengthRejected() {
    Buffer buffer = Buffer.buffer();
    buffer.appendInt(ImMessage.MAGIC_NUMBER);
    buffer.appendByte((byte) 1);
    buffer.appendByte((byte) 0);
    buffer.appendInt(0x10);
    // 负数长度
    buffer.appendInt(-5);
    buffer.appendInt(0);
    buffer.appendInt(0);

    ImMessage message = new ImMessage();
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> message.readFromWire(buffer));
    assertTrue(e.getMessage().contains("messageIdLength"));
  }

  @Test
  @DisplayName("测试 body 长度超出剩余字节 - 抛出异常")
  void testBodyLengthBeyondBufferRejected() {
    Buffer buffer = Buffer.buffer();
    buffer.appendInt(ImMessage.MAGIC_NUMBER);
    buffer.appendByte((byte) 1);
    buffer.appendByte((byte) 0);
    buffer.appendInt(0x10);
    buffer.appendInt(0);
    buffer.appendInt(0);
    // 声明的 body 长度超过帧内剩余字节
    buffer.appendInt(999999);

    ImMessage message = new ImMessage();
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> message.readFromWire(buffer));
    assertTrue(e.getMessage().contains("bodyLength"));
  }

  @Test
  @DisplayName("测试 body 长度超过类型上限 - 抛出异常")
  void testBodyLengthOverLimitRejected() {
    ImMessage original = ImMessage.builder()
        .version((byte) 1)
        .codecId((byte) 0)
        .cmd((byte) 0x10)
        .messageId("m")
        .body("x".getBytes(StandardCharsets.UTF_8))
        .build();
    // 构造帧后手工改写 bodyLength 为超过 MAX_BODY_LENGTH 的值
    Buffer buffer = original.encodeToWire();
    buffer.setInt(buffer.length() - 5, ImMessage.MAX_BODY_LENGTH + 1);

    Buffer payload = buffer.getBuffer(4, buffer.length());
    ImMessage message = new ImMessage();
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> message.readFromWire(payload));
    assertTrue(e.getMessage().contains("bodyLength"));
  }

  @Test
  @DisplayName("测试变长头数量超上限 - 抛出异常")
  void testHeaderCountOverLimitRejected() {
    Buffer buffer = Buffer.buffer();
    buffer.appendInt(ImMessage.MAGIC_NUMBER);
    buffer.appendByte((byte) 1);
    buffer.appendByte((byte) 0);
    buffer.appendInt(0x10);
    buffer.appendInt(0);
    // 头数量超过上限
    buffer.appendInt(ImMessage.MAX_VAR_HEADER_COUNT + 1);
    buffer.appendInt(0);

    ImMessage message = new ImMessage();
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> message.readFromWire(buffer));
    assertTrue(e.getMessage().contains("headersCount"));
  }

  @Test
  @DisplayName("测试变长头 value 长度截断 - 抛出异常")
  void testHeaderValueLengthTruncatedRejected() {
    Buffer buffer = Buffer.buffer();
    buffer.appendInt(ImMessage.MAGIC_NUMBER);
    buffer.appendByte((byte) 1);
    buffer.appendByte((byte) 0);
    buffer.appendInt(0x10);
    buffer.appendInt(0);
    buffer.appendInt(1);
    // key: 长度 1 + 内容 "k"
    buffer.appendInt(1);
    buffer.appendString("k");
    // value: 声明长度 100 但无内容
    buffer.appendInt(100);

    ImMessage message = new ImMessage();
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> message.readFromWire(buffer));
    assertTrue(e.getMessage().contains("headerValueLength"));
  }

  @Test
  @DisplayName("测试正常消息在长度上限内编解码不受影响")
  void testNormalMessageWithinLimitsStillWorks() {
    Map<String, String> headers = new HashMap<>();
    headers.put("userId", "12345");
    headers.put("peerId", "67890");

    ImMessage original = ImMessage.builder()
        .version((byte) 1)
        .codecId((byte) 0)
        .cmd(0x10)
        .messageId("msg-limits-ok")
        .body("hello".getBytes(StandardCharsets.UTF_8))
        .varHeaders(headers)
        .build();

    Buffer buffer = original.encodeToWire();
    ImMessage decoded = new ImMessage();
    decoded.readFromWire(buffer.getBuffer(4, buffer.length()));

    assertEquals(original.getMessageId(), decoded.getMessageId());
    assertEquals(headers, decoded.getVarHeaders());
  }
}
