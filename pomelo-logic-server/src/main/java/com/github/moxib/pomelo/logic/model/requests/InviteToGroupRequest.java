package com.github.moxib.pomelo.logic.model.requests;

import com.github.moxib.pomelo.proto.group.GroupMgmtProto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 邀请入群请求 DTO（PB InviteToGroupReq / JSON 共用）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InviteToGroupRequest(
  String groupId,
  String userId
) {
  public static InviteToGroupRequest fromProto(GroupMgmtProto.InviteToGroupReq proto) {
    return new InviteToGroupRequest(String.valueOf(proto.getGroupId()), String.valueOf(proto.getUserId()));
  }
}
