package com.github.moxib.pomelo.logic.infrastructure;

/**
 * Caffeine 异步缓存键：群 ID + 用户 ID 组合。
 */
record GroupMemberCacheKey(long groupId, long userId) {}
