package com.github.moxib.pomelo.logic.model.requests;

import com.github.moxib.pomelo.proto.call.CallProto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * C→S 发起通话（媒体类型随 proto 枚举：0=audio 1=video）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CallInviteRequest(
  long peerId,
  int mediaType
) {
  public static CallInviteRequest fromProto(CallProto.CallInviteReq proto) {
    return new CallInviteRequest(proto.getPeerId(), proto.getMediaTypeValue());
  }
}
