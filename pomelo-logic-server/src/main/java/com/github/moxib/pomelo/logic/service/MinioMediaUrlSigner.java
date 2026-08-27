package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.logic.infrastructure.ObjectPresigner;
import com.github.moxib.pomelo.proto.common.CommonProto;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MinioMediaUrlSigner implements MediaUrlSigner {

  private static final Logger LOG = LoggerFactory.getLogger(MinioMediaUrlSigner.class);

  private final ObjectPresigner presigner;

  public MinioMediaUrlSigner(ObjectPresigner presigner) {
    this.presigner = presigner;
  }

  @Override
  public String signContent(int msgType, String content) {
    if (!isMediaType(msgType)) {
      return content;
    }
    if (content == null || content.isBlank()) {
      return content;
    }
    try {
      JsonObject obj = new JsonObject(content);
      String key = obj.getString("key");
      if (key == null || key.isBlank()) {
        return content;
      }
      obj.put("url", presigner.presignGet(key));
      String thumb = obj.getString("thumb");
      if (thumb != null && !thumb.isBlank()) {
        obj.put("thumbUrl", presigner.presignGet(thumb));
      }
      return obj.encode();
    } catch (Exception e) {
      LOG.warn("媒体 content 签名失败，原样返回: {}", e.getMessage());
      return content;
    }
  }

  private static boolean isMediaType(int msgType) {
    return msgType == CommonProto.MsgType.MSG_TYPE_IMAGE_VALUE
      || msgType == CommonProto.MsgType.MSG_TYPE_VOICE_VALUE
      || msgType == CommonProto.MsgType.MSG_TYPE_VIDEO_VALUE
      || msgType == CommonProto.MsgType.MSG_TYPE_FILE_VALUE
      || msgType == CommonProto.MsgType.MSG_TYPE_EMOJI_VALUE;
  }
}
