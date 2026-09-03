package com.github.moxib.pomelo.logic.model.requests;

import com.github.moxib.pomelo.proto.group.GroupMgmtProto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 获取群信息请求 DTO（PB GetGroupInfoReq / JSON 共用）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GetGroupInfoRequest(
  String groupId
) {
  public static GetGroupInfoRequest fromProto(GroupMgmtProto.GetGroupInfoReq proto) {
    return new GetGroupInfoRequest(String.valueOf(proto.getGroupId()));
  }
}
