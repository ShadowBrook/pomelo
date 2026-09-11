package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.logic.infrastructure.ObjectPresigner;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MinioMediaUrlSignerTest {

  /** 新版随机对象名 */
  private static final String RANDOM_KEY = "image/100/20260910/0123456789abcdef0123456789abcdef.jpg";
  /** 存量 Snowflake 对象名 */
  private static final String LEGACY_KEY = "voice/100/20260910/8841234567890123456.m4a";

  private static final ObjectPresigner fake = new ObjectPresigner() {
    @Override public String presignPut(String k, String ct) { return "http://put/" + k; }
    @Override public String presignGet(String k) { return "http://get/" + k; }
  };

  private final MediaUrlSigner signer = new MinioMediaUrlSigner(fake);

  @Test
  void injectsUrlForImage() {
    String out = signer.signContent(2, "{\"key\":\"" + RANDOM_KEY + "\",\"width\":1080}");
    JsonObject obj = new JsonObject(out);
    assertEquals("http://get/" + RANDOM_KEY, obj.getString("url"));
    assertEquals(RANDOM_KEY, obj.getString("key"));
  }

  @Test
  void injectsThumbUrlForVideo() {
    String key = "video/100/20260910/aaaabbbbccccddddeeeeffff00001111.mp4";
    String thumb = "video/100/20260910/11112222333344445555666677778888.jpg";
    String out = signer.signContent(4, "{\"key\":\"" + key + "\",\"thumb\":\"" + thumb + "\"}");
    JsonObject obj = new JsonObject(out);
    assertEquals("http://get/" + key, obj.getString("url"));
    assertEquals("http://get/" + thumb, obj.getString("thumbUrl"));
  }

  @Test
  void signsLegacySnowflakeKey() {
    String out = signer.signContent(3, "{\"key\":\"" + LEGACY_KEY + "\"}");
    assertEquals("http://get/" + LEGACY_KEY, new JsonObject(out).getString("url"));
  }

  @Test
  void doesNotSignIllFormedKey() {
    // content 是客户端自由文本，形如 URL / 路径穿越 / 空目录的 key 都不是服务端签发的对象
    for (String bad : new String[]{"http://evil/x.jpg", "../../secrets", "img/1.jpg", "image/100/x.jpg", "image/../100/20260910/abc.jpg"}) {
      String in = "{\"key\":\"" + bad + "\"}";
      String out = signer.signContent(2, in);
      assertNull(new JsonObject(out).getString("url"), "不应为非法 key 签名: " + bad);
    }
  }

  @Test
  void passesThroughText() {
    String in = "hello";
    assertSame(in, signer.signContent(1, in));
  }

  @Test
  void passesThroughMissingKey() {
    String in = "{\"width\":100}";
    assertEquals(in, signer.signContent(2, in));
  }

  @Test
  void passesThroughBadJson() {
    String in = "not-json";
    assertEquals(in, signer.signContent(2, in));
  }
}
