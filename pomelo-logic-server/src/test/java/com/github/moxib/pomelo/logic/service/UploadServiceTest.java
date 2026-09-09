package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.logic.id.SnowflakeIdGenerator;
import com.github.moxib.pomelo.logic.infrastructure.ObjectPresigner;
import com.github.moxib.pomelo.proto.common.CommonProto;
import com.github.moxib.pomelo.proto.upload.UploadProto;
import org.junit.jupiter.api.Test;

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

  private ImMessage pbReq(int mediaType, String fileName, long size, String userId) {
    UploadProto.UploadReq body = UploadProto.UploadReq.newBuilder()
      .setMediaType(mediaType).setFileName(fileName).setSize(size).build();
    Map<String, String> headers = new HashMap<>();
    if (userId != null) {
      headers.put("userId", userId);
    }
    return ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER).version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId((byte) 0).cmd(CommonProto.Cmd.CMD_UPLOAD_REQ_VALUE)
      .messageId("u-1").body(body.toByteArray()).varHeaders(headers).build();
  }

  private int respCode(ImMessage resp) throws Exception {
    // 成功返回 UploadResp，失败返回 ErrorBody，两者 field 1 都是 code
    return UploadProto.UploadResp.parseFrom(resp.getBody()).getCode();
  }

  private UploadProto.UploadResp uploadResp(ImMessage resp) throws Exception {
    return UploadProto.UploadResp.parseFrom(resp.getBody());
  }

  private ImMessage process(ImMessage req) throws Exception {
    return service.process(req).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  @Test
  void validRequestReturnsObjectKeyAndPresignedUrl() throws Exception {
    ImMessage resp = process(pbReq(2, "a.jpg", 100, "100"));
    UploadProto.UploadResp body = uploadResp(resp);
    assertEquals(0, body.getCode());
    String key = body.getObjectKey();
    assertTrue(key.matches("image/100/\\d{8}/\\d+\\.jpg"), "key 格式不符: " + key);
    assertEquals("http://put/" + key, body.getPresignedUrl());
    assertTrue(body.getExpireAt() > 0);
  }

  @Test
  void rejectsUnknownMediaType() throws Exception {
    ImMessage resp = process(pbReq(1, "a.jpg", 100, "100"));
    assertNotEquals(0, respCode(resp));
  }

  @Test
  void rejectsOversize() throws Exception {
    ImMessage resp = process(pbReq(2, "a.jpg", 999999999, "100"));
    assertNotEquals(0, respCode(resp));
  }

  @Test
  void rejectsDisallowedExtension() throws Exception {
    ImMessage resp = process(pbReq(2, "a.exe", 100, "100"));
    assertNotEquals(0, respCode(resp));
  }

  @Test
  void rejectsUnauthenticated() throws Exception {
    ImMessage resp = process(pbReq(2, "a.jpg", 100, null));
    assertNotEquals(0, respCode(resp));
  }

  @Test
  void rejectsPathTraversalUserId() throws Exception {
    ImMessage resp = process(pbReq(2, "a.jpg", 100, "foo/../bar"));
    assertNotEquals(0, respCode(resp));
  }
}
