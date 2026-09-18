package com.github.moxib.pomelo.logic.model.requests;

import com.github.moxib.pomelo.proto.call.CallProto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * C→S 发起通话（媒体类型随 proto 枚举：0=audio 1=video）。
 * peerIds 为被叫列表（不含主叫）；proto 兼容层保证非空：1:1 时为单元素（peer_id）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CallInviteRequest(
  long peerId,
  int mediaType,
  List<Long> peerIds,
  long groupId
) {
  public static CallInviteRequest fromProto(CallProto.CallInviteReq proto) {
    List<Long> peers = proto.getPeerIdsList().stream().map(Long::longValue).toList();
    return new CallInviteRequest(proto.getPeerId(), proto.getMediaTypeValue(), peers, proto.getGroupId());
  }
}
