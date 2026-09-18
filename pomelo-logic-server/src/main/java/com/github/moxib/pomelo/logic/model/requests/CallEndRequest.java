package com.github.moxib.pomelo.logic.model.requests;

import com.github.moxib.pomelo.proto.call.CallProto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * C→S 结束通话。最终结束原因由服务端按「发送者角色 + 通话状态」裁定，
 * 请求里的 reason 仅作参考（防客户端伪造"对方挂断"类展示）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CallEndRequest(
  String callId,
  int reason
) {
  public static CallEndRequest fromProto(CallProto.CallEndReq proto) {
    return new CallEndRequest(proto.getCallId(), proto.getReasonValue());
  }
}
