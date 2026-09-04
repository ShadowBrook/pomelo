package com.github.moxib.pomelo.logic.model;

/**
 * 用户 ID 信息 — userId（数字 id 的字符串，与 session/路由标识一致）+ 显示名。
 * 用于按数字 id 批量补全消息发送者的展示信息。
 */
public record UserIdInfo(String userId, String userName, String nickname) {}
