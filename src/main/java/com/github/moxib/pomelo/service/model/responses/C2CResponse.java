package com.github.moxib.pomelo.service.model.responses;

/**
 * C2C 单聊响应 DTO。
 */
public record C2CResponse(int code, String message, long messageId, long serverTime, Long seq) {}
