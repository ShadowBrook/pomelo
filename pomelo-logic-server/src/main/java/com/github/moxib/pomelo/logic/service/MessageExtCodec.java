package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.proto.common.CommonProto;
import io.vertx.core.json.JsonObject;

import java.util.Map;

/**
 * MessageContent.ext 的持久化编解码。
 * ext 是客户端可控的元数据通道（如 @ 提及 mentioned_user_ids），服务端原样存储/回传，
 * 不解析业务语义；入库序列化为 JSON 对象，读侧再还原进 MessageContent.ext。
 */
public final class MessageExtCodec {

  private MessageExtCodec() {
  }

  /** proto ext map → JSON 串；无扩展返回 null（入库存 NULL） */
  public static String toJson(CommonProto.MessageContent message) {
    if (message.getExtCount() == 0) {
      return null;
    }
    JsonObject o = new JsonObject();
    for (Map.Entry<String, String> e : message.getExtMap().entrySet()) {
      o.put(e.getKey(), e.getValue());
    }
    return o.encode();
  }

  /** JSON 串 → 注入 MessageContent.Builder.ext；非法 JSON 容错忽略 */
  public static void inject(CommonProto.MessageContent.Builder builder, String extJson) {
    if (extJson == null || extJson.isBlank()) {
      return;
    }
    try {
      JsonObject o = new JsonObject(extJson);
      for (Map.Entry<String, Object> e : o) {
        if (e.getValue() instanceof String s) {
          builder.putExt(e.getKey(), s);
        }
      }
    } catch (Exception ignored) {
      // 扩展数据损坏不应影响消息本身的可读性
    }
  }
}
