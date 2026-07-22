package com.github.moxib.pomelo.service.model.responses;

/**
 * 登录/认证响应 DTO。
 */
public record LoginResponse(int code, String message, String userId, String userName, String nickname, String avatar) {}
