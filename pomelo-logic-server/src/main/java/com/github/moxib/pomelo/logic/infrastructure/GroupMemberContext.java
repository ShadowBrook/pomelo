package com.github.moxib.pomelo.logic.infrastructure;

/**
 * 群成员上下文——发送消息路径专用，合并 group 存在性 + 成员身份 + 禁言状态为单次查询。
 *
 * @param groupExists 群是否存在
 * @param groupName   群名称（群存在时非 null）
 * @param isMember    发送者是否为群成员
 * @param isMuted     发送者是否被禁言（muted_until > now）
 */
public record GroupMemberContext(boolean groupExists, String groupName,
                                  boolean isMember, boolean isMuted) {}
