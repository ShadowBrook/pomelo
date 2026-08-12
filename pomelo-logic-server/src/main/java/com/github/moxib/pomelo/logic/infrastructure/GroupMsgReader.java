package com.github.moxib.pomelo.logic.infrastructure;

/**
 * 群消息已读用户信息。
 */
public record GroupMsgReader(long userId, String nickname, String avatar) {}
