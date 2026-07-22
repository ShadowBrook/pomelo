package com.github.moxib.pomelo.service.model.responses;

/**
 * 好友操作响应 DTO（Add/Accept/Delete 共用，都是 code + message）。
 */
public record FriendOpResponse(int code, String message) {}
