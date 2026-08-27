package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.logic.infrastructure.ObjectPresigner;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MinioMediaUrlSignerTest {

  private static final ObjectPresigner fake = new ObjectPresigner() {
    @Override public String presignPut(String k, String ct) { return "http://put/" + k; }
    @Override public String presignGet(String k) { return "http://get/" + k; }
  };

  private final MediaUrlSigner signer = new MinioMediaUrlSigner(fake);

  @Test
  void injectsUrlForImage() {
    String out = signer.signContent(2, "{\"key\":\"img/1.jpg\",\"width\":1080}");
    JsonObject obj = new JsonObject(out);
    assertEquals("http://get/img/1.jpg", obj.getString("url"));
    assertEquals("img/1.jpg", obj.getString("key"));
  }

  @Test
  void injectsThumbUrlForVideo() {
    String out = signer.signContent(4, "{\"key\":\"v.mp4\",\"thumb\":\"v_thumb.jpg\"}");
    JsonObject obj = new JsonObject(out);
    assertEquals("http://get/v.mp4", obj.getString("url"));
    assertEquals("http://get/v_thumb.jpg", obj.getString("thumbUrl"));
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
