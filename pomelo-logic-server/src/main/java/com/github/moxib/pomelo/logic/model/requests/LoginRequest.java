package com.github.moxib.pomelo.logic.model.requests;

import com.github.moxib.pomelo.proto.auth.AuthProto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 登录/认证请求 DTO（PB AuthReq / JSON 共用）。
 * userId 和 userName 来自 varHeaders 而非 proto body，此处为可选字段。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LoginRequest(
  String token,
  String userId,
  String userName,
  String deviceId,
  String platform,
  String appVersion
) {
  public static LoginRequest fromProto(AuthProto.AuthReq proto) {
    return new LoginRequest(
      proto.getToken(), "", "",
      proto.getDeviceId(), proto.getPlatform(), proto.getAppVersion());
  }
}
