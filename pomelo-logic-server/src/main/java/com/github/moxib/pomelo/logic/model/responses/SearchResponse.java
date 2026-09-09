package com.github.moxib.pomelo.logic.model.responses;

import java.util.List;

/**
 * 用户搜索响应 DTO。
 */
public record SearchResponse(int code, String message, List<UserInfo> users) {
  public record UserInfo(String userId, String userName, String nickname, String avatar) {}
}
