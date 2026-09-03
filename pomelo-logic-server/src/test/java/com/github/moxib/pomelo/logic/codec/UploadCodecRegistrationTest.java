package com.github.moxib.pomelo.logic.codec;

import com.github.moxib.pomelo.logic.model.requests.UploadRequest;
import com.github.moxib.pomelo.proto.common.CommonProto;
import com.github.moxib.pomelo.proto.upload.UploadProto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UploadCodecRegistrationTest {

  @Test
  void uploadReqCodecIsRegistered() {
    assertNotNull(CodecRegistryHolder.REGISTRY.getCodec(CommonProto.Cmd.CMD_UPLOAD_REQ_VALUE, 0));
  }

  @Test
  void fromProtoMapsAllFields() {
    UploadProto.UploadReq proto = UploadProto.UploadReq.newBuilder()
      .setMediaType(2).setFileName("a.jpg").setSize(123L).setContentType("image/jpeg").build();
    UploadRequest req = UploadRequest.fromProto(proto);
    assertEquals(2, req.mediaType());
    assertEquals("a.jpg", req.fileName());
    assertEquals(123L, req.size());
    assertEquals("image/jpeg", req.contentType());
  }
}
