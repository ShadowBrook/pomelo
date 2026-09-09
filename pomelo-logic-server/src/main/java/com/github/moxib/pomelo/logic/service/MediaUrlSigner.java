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
}
