package com.github.moxib.pomelo.logic.model.requests;

import com.github.moxib.pomelo.proto.call.CallProto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * C→S 接听通话。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CallAcceptRequest(
  String callId
) {
  public static CallAcceptRequest fromProto(CallProto.CallAcceptReq proto) {
    return new CallAcceptRequest(proto.getCallId());
  }
}
