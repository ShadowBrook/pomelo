package com.github.moxib.pomelo.logic.model.requests;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.github.moxib.pomelo.proto.group.GroupMgmtProto;

/**
 * 群主转让请求 DTO（PB TransferGroupReq / JSON 共用）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransferGroupRequest(
  String groupId,
  String targetUserId
) {
  public static TransferGroupRequest fromProto(GroupMgmtProto.TransferGroupReq proto) {
    return new TransferGroupRequest(String.valueOf(proto.getGroupId()), String.valueOf(proto.getTargetUserId()));
  }
}
