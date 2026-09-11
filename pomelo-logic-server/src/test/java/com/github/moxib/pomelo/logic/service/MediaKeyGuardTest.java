package com.github.moxib.pomelo.logic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 媒体 key 归属校验策略。
 * <p>
 * 读侧会为 content 里的 key 签发 presigned GET URL，而 content 由客户端自由填写；
 * 发送侧必须保证"普通媒体只能引用自己上传的对象"，否则任何人发一条自指消息
 * 就能给他人对象换取下载链接。
 */
@DisplayName("媒体 key 归属校验")
class MediaKeyGuardTest {

  private static final int IMAGE = 2;
  private static final int VIDEO = 4;
  private static final int TEXT = 1;
  private static final int FORWARD = 8;
  private static final int REPLY = 9;

  private static final String SENDER = "100";

  private static final String OWN_KEY = "image/100/20260910/0123456789abcdef0123456789abcdef.jpg";
  private static final String OTHER_KEY = "image/200/20260910/fedcba9876543210fedcba9876543210.jpg";

  @Test
  @DisplayName("普通媒体引用自己上传的对象 → 放行")
  void allowsOwnMedia() {
    assertNull(MediaKeyGuard.validate(IMAGE, "{\"key\":\"" + OWN_KEY + "\"}", SENDER));
  }

  @Test
  @DisplayName("普通媒体引用他人对象 → 拒绝")
  void rejectsForeignMedia() {
    String error = MediaKeyGuard.validate(IMAGE, "{\"key\":\"" + OTHER_KEY + "\"}", SENDER);
    assertNotNull(error, "引用他人对象必须被拒，否则读侧会为其签发下载 URL");
  }

  @Test
  @DisplayName("普通媒体 thumb 也受同样约束")
  void rejectsForeignThumb() {
    String content = "{\"key\":\"" + OWN_KEY + "\",\"thumb\":\"" + OTHER_KEY + "\"}";
    assertNotNull(MediaKeyGuard.validate(VIDEO, content, SENDER));
  }

  @Test
  @DisplayName("无法通过拼接伪造 key 绕过（非服务端形态一律拒绝）")
  void rejectsIllFormedKey() {
    assertNotNull(MediaKeyGuard.validate(IMAGE, "{\"key\":\"image/100/x.jpg\"}", SENDER));
    assertNotNull(MediaKeyGuard.validate(IMAGE, "{\"key\":\"../../etc/passwd\"}", SENDER));
    assertNotNull(MediaKeyGuard.validate(IMAGE, "{\"key\":\"http://evil/a.jpg\"}", SENDER));
  }

  @Test
  @DisplayName("合并转发可以引用他人的媒体（原消息快照），但 key 仍需形态合法")
  void forwardAllowsForeignKeyButRequiresShape() {
    String ok = "{\"t\":\"记录\",\"items\":[{\"msgType\":2,\"media\":{\"key\":\"" + OTHER_KEY + "\"}}]}";
    assertNull(MediaKeyGuard.validate(FORWARD, ok, SENDER));

    String bad = "{\"items\":[{\"msgType\":2,\"media\":{\"key\":\"http://evil/a.jpg\"}}]}";
    assertNotNull(MediaKeyGuard.validate(FORWARD, bad, SENDER));
  }

  @Test
  @DisplayName("引用消息可以带他人的 thumb 与被引用体")
  void replyAllowsForeignContent() {
    String content = "{\"reply\":{\"senderId\":\"200\",\"thumb\":\"" + OTHER_KEY + "\"},"
      + "\"body\":{\"msgType\":2,\"content\":\"{\\\"key\\\":\\\"" + OTHER_KEY + "\\\"}\"}}";
    assertNull(MediaKeyGuard.validate(REPLY, content, SENDER));
  }

  @Test
  @DisplayName("非媒体类型与非法 JSON 不拦截")
  void ignoresNonMediaAndBadJson() {
    assertNull(MediaKeyGuard.validate(TEXT, "hello", SENDER));
    assertNull(MediaKeyGuard.validate(IMAGE, "not-json", SENDER));
    assertNull(MediaKeyGuard.validate(IMAGE, null, SENDER));
  }
}
