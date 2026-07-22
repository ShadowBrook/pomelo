package com.github.moxib.pomelo.service.model;

/**
 * 用户 ID 信息 — userId (NanoID) + 显示名。
 * 用于内部 numeric id → 外部标识的批量转换。
 */
public record UserIdInfo(String userId, String userName, String nickname) {}
