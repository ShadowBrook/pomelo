package com.github.moxib.pomelo.logic.service;

/**
 * 媒体消息读侧签名器：把 content JSON 里的稳定 key 换成 presigned GET URL。
 */
public interface MediaUrlSigner {

  /**
   * msgType ∈ {2..6} 且 content 可解析出 key 时，注入 url/thumbUrl；否则原样返回。
   * 永不抛异常。
   */
  String signContent(int msgType, String content);

  /**
   * 头像读侧签名：im_user.avatar 存的是对象 key，转换为 presigned GET URL。
   * 空值返回空串；非 key 形态（历史存量外部 URL）原样返回。
   * 默认实现为透传，测试桩无需关心签名行为。
   */
  default String signAvatar(String avatar) {
    return avatar == null ? "" : avatar;
  }
}
