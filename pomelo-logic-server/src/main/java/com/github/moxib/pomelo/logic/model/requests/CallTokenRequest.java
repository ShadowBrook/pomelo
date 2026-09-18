package com.github.moxib.pomelo.logic.model.requests;

import com.github.moxib.pomelo.proto.call.CallProto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * C→S 重新获取入会材料（断线重连）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CallTokenRequest(
  String callId
) {
  public static CallTokenRequest fromProto(CallProto.CallTokenReq proto) {
    return new CallTokenRequest(proto.getCallId());
  }
}
