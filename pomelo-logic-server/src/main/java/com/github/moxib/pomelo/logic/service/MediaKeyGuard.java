package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.proto.common.CommonProto;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * 发送侧媒体 key 归属校验。
 * <p>
 * content 是客户端自由文本、原样入库，其中的 {@code key} 决定了读侧要为哪个对象签发
 * presigned GET URL。若不校验，任意认证用户发一条 {@code {"key":"<他人对象>"}} 的
 * 消息再拉取历史，就能拿到该对象的下载链接（bucket 级越权读）。
 * <p>
 * 规则：
 * <ul>
 *   <li>普通媒体（2-6）：key / thumb 必须是服务端签发形态，且上传者就是发送者</li>
 *   <li>合并转发（8）：items[].media 里的 key 属于原消息发送者，只校验形态</li>
 *   <li>引用（9）：引用的是他人消息快照，只校验形态</li>
 * </ul>
 * 形态校验之外还依赖对象名不可枚举（128 位随机，见 {@link ObjectKeys}），
 * 否则"猜测他人 key"这一步不需要经过任何一条消息。
 */
final class MediaKeyGuard {

  private MediaKeyGuard() {
  }

  /** 校验通过返回 null；否则返回可直接回给客户端的错误说明 */
  static String validate(int msgType, String content, String senderUserId) {
    return check(msgType, content, senderUserId, true);
  }

  private static String check(int msgType, String content, String senderUserId, boolean requireOwner) {
    if (content == null || content.isBlank()) {
      return null;
    }
    try {
      if (msgType == CommonProto.MsgType.MSG_TYPE_FORWARD_VALUE) {
        return checkForward(content);
      }
      if (msgType == CommonProto.MsgType.MSG_TYPE_REPLY_VALUE) {
        return checkReply(content, senderUserId);
      }
      if (!isMediaType(msgType)) {
        return null;
      }
      JsonObject obj = new JsonObject(content);
      String error = checkKey(obj.getString("key"), requireOwner, senderUserId);
      return error != null ? error : checkKey(obj.getString("thumb"), requireOwner, senderUserId);
    } catch (Exception e) {
      // content 不是合法 JSON：读侧同样解析不出 key、签不出 url，不构成越权
      return null;
    }
  }

  private static String checkForward(String content) {
    JsonArray items = new JsonObject(content).getJsonArray("items");
    if (items == null) {
      return null;
    }
    for (int i = 0; i < items.size(); i++) {
      JsonObject item = items.getJsonObject(i);
      JsonObject media = item != null ? item.getJsonObject("media") : null;
      if (media == null) {
        continue;
      }
      // 转发项引用的是原消息发送者的对象，这里只保证 key 是服务端签发过的形态
      String error = checkKey(media.getString("key"), false, null);
      if (error == null) {
        error = checkKey(media.getString("thumb"), false, null);
      }
      if (error != null) {
        return error;
      }
    }
    return null;
  }

  private static String checkReply(String content, String senderUserId) {
    JsonObject obj = new JsonObject(content);
    JsonObject reply = obj.getJsonObject("reply");
    if (reply != null) {
      String error = checkKey(reply.getString("thumb"), false, null);
      if (error != null) {
        return error;
      }
    }
    JsonObject body = obj.getJsonObject("body");
    if (body == null || body.getInteger("msgType") == null) {
      return null;
    }
    // 引用体是对方消息的快照，不要求归属
    return check(body.getInteger("msgType"), body.getString("content"), senderUserId, false);
  }

  private static String checkKey(String key, boolean requireOwner, String senderUserId) {
    if (key == null || key.isBlank()) {
      return null;
    }
    if (!ObjectKeys.isWellFormed(key)) {
      return "非法的媒体对象 key";
    }
    if (requireOwner && !ObjectKeys.ownerOf(key).equals(senderUserId)) {
      return "无权引用他人上传的媒体对象";
    }
    return null;
  }

  private static boolean isMediaType(int msgType) {
    return msgType == CommonProto.MsgType.MSG_TYPE_IMAGE_VALUE
      || msgType == CommonProto.MsgType.MSG_TYPE_VOICE_VALUE
      || msgType == CommonProto.MsgType.MSG_TYPE_VIDEO_VALUE
      || msgType == CommonProto.MsgType.MSG_TYPE_FILE_VALUE
      || msgType == CommonProto.MsgType.MSG_TYPE_EMOJI_VALUE;
  }
}
