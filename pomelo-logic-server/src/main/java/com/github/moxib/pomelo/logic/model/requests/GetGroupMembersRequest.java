package com.github.moxib.pomelo.logic.model.requests;

import com.github.moxib.pomelo.proto.group.GroupMgmtProto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 获取群成员请求 DTO（PB GetGroupMembersReq / JSON 共用）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GetGroupMembersRequest(
  String groupId
) {
  public static GetGroupMembersRequest fromProto(GroupMgmtProto.GetGroupMembersReq proto) {
    return new GetGroupMembersRequest(String.valueOf(proto.getGroupId()));
  }
}
