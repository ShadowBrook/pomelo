package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.logic.infrastructure.ObjectPresigner;
import com.github.moxib.pomelo.proto.common.CommonProto;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 媒体读侧签名器。content 约定：只存稳定对象 key，读侧注入 presigned GET URL。
 * <p>
 * 处理的 content 形态按 msgType 分发：
 * <ul>
 *   <li>普通媒体（2-6）：{key, thumb?, ...} → 注入 url / thumbUrl</li>
 *   <li>合并转发（8）：{items:[{media:{key,...}}]} → 逐项注入 media.url / media.thumbUrl</li>
 *   <li>引用（9）：{reply:{thumb?}, body:{msgType, content}} → body.content 递归签名（body 可为文本/媒体/转发），reply.thumb 注入 thumbUrl</li>
 * </ul>
 * 永不抛异常；解析失败或非媒体类型原样返回。
 */
public class MinioMediaUrlSigner implements MediaUrlSigner {

  private static final Logger LOG = LoggerFactory.getLogger(MinioMediaUrlSigner.class);

  private final ObjectPresigner presigner;

  public MinioMediaUrlSigner(ObjectPresigner presigner) {
    this.presigner = presigner;
  }

  @Override
  public String signContent(int msgType, String content) {
    if (content == null || content.isBlank()) {
      return content;
    }
    if (msgType == CommonProto.MsgType.MSG_TYPE_FORWARD_VALUE) {
      return signForward(content);
    }
    if (msgType == CommonProto.MsgType.MSG_TYPE_REPLY_VALUE) {
      return signReply(content);
    }
    if (!isMediaType(msgType)) {
      return content;
    }
    try {
      JsonObject obj = new JsonObject(content);
      String key = obj.getString("key");
      if (!ObjectKeys.isWellFormed(key)) {
        return content;
      }
      obj.put("url", presigner.presignGet(key));
      String thumb = obj.getString("thumb");
      if (ObjectKeys.isWellFormed(thumb)) {
        obj.put("thumbUrl", presigner.presignGet(thumb));
      }
      return obj.encode();
    } catch (Exception e) {
      LOG.warn("媒体 content 签名失败，原样返回: {}", e.getMessage());
      return content;
    }
  }

  /** 合并转发：为 items[].media 内嵌 key/thumb 注入 presigned GET */
  private String signForward(String content) {
    try {
      JsonObject obj = new JsonObject(content);
      JsonArray items = obj.getJsonArray("items");
      if (items != null) {
        for (int i = 0; i < items.size(); i++) {
          JsonObject media = items.getJsonObject(i).getJsonObject("media");
          if (media == null) {
            continue;
          }
          signMediaObject(media);
        }
      }
      return obj.encode();
    } catch (Exception e) {
      LOG.warn("转发 content 签名失败，原样返回: {}", e.getMessage());
      return content;
    }
  }

  /** 引用：body.content 递归签名（文本透传/媒体签 url/转发签 items），reply.thumb 注入 thumbUrl */
  private String signReply(String content) {
    try {
      JsonObject obj = new JsonObject(content);
      JsonObject body = obj.getJsonObject("body");
      if (body != null && body.getInteger("msgType") != null) {
        int bodyType = body.getInteger("msgType");
        String signed = signContent(bodyType, body.getString("content"));
        body.put("content", signed);
      }
      JsonObject reply = obj.getJsonObject("reply");
      if (reply != null) {
        String th = reply.getString("thumb");
        if (ObjectKeys.isWellFormed(th)) {
          reply.put("thumbUrl", presigner.presignGet(th));
        }
      }
      return obj.encode();
    } catch (Exception e) {
      LOG.warn("引用 content 签名失败，原样返回: {}", e.getMessage());
      return content;
    }
  }

  private void signMediaObject(JsonObject media) {
    String k = media.getString("key");
    if (ObjectKeys.isWellFormed(k)) {
      media.put("url", presigner.presignGet(k));
    }
    String th = media.getString("thumb");
    if (ObjectKeys.isWellFormed(th)) {
      media.put("thumbUrl", presigner.presignGet(th));
    }
  }

  private static boolean isMediaType(int msgType) {
    return msgType == CommonProto.MsgType.MSG_TYPE_IMAGE_VALUE
      || msgType == CommonProto.MsgType.MSG_TYPE_VOICE_VALUE
      || msgType == CommonProto.MsgType.MSG_TYPE_VIDEO_VALUE
      || msgType == CommonProto.MsgType.MSG_TYPE_FILE_VALUE
      || msgType == CommonProto.MsgType.MSG_TYPE_EMOJI_VALUE;
  }

  /**
   * 头像读侧签名：avatar 列存对象 key 时签发 presigned GET；
   * 空值返回空串，历史存量外部 URL 原样返回。
   */
  @Override
  public String signAvatar(String avatar) {
    if (avatar == null || avatar.isBlank()) {
      return "";
    }
    if (!ObjectKeys.isWellFormed(avatar)) {
      return avatar;
    }
    try {
      return presigner.presignGet(avatar);
    } catch (Exception e) {
      LOG.warn("头像 URL 签名失败，原样返回: {}", e.getMessage());
      return avatar;
    }
  }
}
