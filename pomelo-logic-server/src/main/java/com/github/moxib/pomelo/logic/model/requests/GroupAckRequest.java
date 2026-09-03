package com.github.moxib.pomelo.logic.model.requests;

import com.github.moxib.pomelo.proto.group.GroupMgmtProto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 群 ACK 请求 DTO（PB GroupAckReq / JSON 共用）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GroupAckRequest(
  String groupId,
  long lastReadSeq
) {
  public static GroupAckRequest fromProto(GroupMgmtProto.GroupAckReq proto) {
    return new GroupAckRequest(String.valueOf(proto.getGroupId()), proto.getLastReadSeq());
  }
}
