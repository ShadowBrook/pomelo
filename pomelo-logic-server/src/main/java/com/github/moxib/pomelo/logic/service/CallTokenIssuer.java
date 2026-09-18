package com.github.moxib.pomelo.logic.service;

/**
 * 通话入会凭证签发 abstraction（CallService 依赖此接口，测试可替换）。
 */
public interface CallTokenIssuer {

  /**
   * 为参与者签发 LiveKit 入会 token（可发布可订阅，绑定单房间）。
   * 配置缺失/内置密钥时抛 {@link IllegalStateException}。
   */
  String issue(long userId, String room);

  /** 浏览器可达的 LiveKit 信令地址（room.connect 的 url） */
  String wsUrl();
}
