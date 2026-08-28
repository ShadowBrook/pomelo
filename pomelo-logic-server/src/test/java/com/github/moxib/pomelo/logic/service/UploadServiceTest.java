package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.logic.id.SnowflakeIdGenerator;
import com.github.moxib.pomelo.logic.infrastructure.ObjectPresigner;
import com.github.moxib.pomelo.proto.common.CommonProto;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class UploadServiceTest {

  private static final ObjectPresigner fake = new ObjectPresigner() {
    @Override public String presignPut(String k, String ct) { return "http://put/" + k; }
    @Override public String presignGet(String k) { return "http://get/" + k; }
  };

  private final UploadService service = new UploadService(fake, new SnowflakeIdGenerator(1));

  private ImMessage jsonReq(String bodyJson, String userId) {
    byte[] body = new JsonObject(bodyJson).toBuffer().getBytes();
    Map<String, String> headers = new HashMap<>();
    if (userId != null) {
      headers.put("userId", userId);
    }
    return ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER).version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId((byte) 1).cmd(CommonProto.Cmd.CMD_UPLOAD_REQ_VALUE)
      .messageId("u-1").body(body).varHeaders(headers).build();
  }

  private JsonObject respBody(ImMessage resp) {
    return new JsonObject(new String(resp.getBody(), StandardCharsets.UTF_8));
  }

  private ImMessage process(ImMessage req) throws Exception {
    return service.process(req).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  @Test
  void validRequestReturnsObjectKeyAndPresignedUrl() throws Exception {
    ImMessage resp = process(jsonReq("{\"mediaType\":2,\"fileName\":\"a.jpg\",\"size\":100}", "100"));
    JsonObject body = respBody(resp);
    assertEquals(0, body.getInteger("code"));
    String key = body.getString("objectKey");
    assertTrue(key.matches("image/100/\\d{8}/\\d+\\.jpg"), "key 格式不符: " + key);
    assertEquals("http://put/" + key, body.getString("presignedUrl"));
    assertTrue(body.getLong("expireAt") > 0);
  }

  @Test
  void rejectsUnknownMediaType() throws Exception {
    ImMessage resp = process(jsonReq("{\"mediaType\":1,\"fileName\":\"a.jpg\",\"size\":100}", "100"));
    assertNotEquals(0, respBody(resp).getInteger("code"));
  }

  @Test
  void rejectsOversize() throws Exception {
    ImMessage resp = process(jsonReq("{\"mediaType\":2,\"fileName\":\"a.jpg\",\"size\":999999999}", "100"));
    assertNotEquals(0, respBody(resp).getInteger("code"));
  }

  @Test
  void rejectsDisallowedExtension() throws Exception {
    ImMessage resp = process(jsonReq("{\"mediaType\":2,\"fileName\":\"a.exe\",\"size\":100}", "100"));
    assertNotEquals(0, respBody(resp).getInteger("code"));
  }

  @Test
  void rejectsUnauthenticated() throws Exception {
    ImMessage resp = process(jsonReq("{\"mediaType\":2,\"fileName\":\"a.jpg\",\"size\":100}", null));
    assertNotEquals(0, respBody(resp).getInteger("code"));
  }

  @Test
  void rejectsPathTraversalUserId() throws Exception {
    ImMessage resp = process(jsonReq("{\"mediaType\":2,\"fileName\":\"a.jpg\",\"size\":100}", "foo/../bar"));
    assertNotEquals(0, respBody(resp).getInteger("code"));
  }
}
