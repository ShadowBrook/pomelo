package com.github.moxib.pomelo.logic.model.requests;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.github.moxib.pomelo.proto.group.GroupMgmtProto;

/**
 * 修改群信息请求 DTO（PB UpdateGroupReq / JSON 共用）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateGroupRequest(
  String groupId,
  String name
) {
  public static UpdateGroupRequest fromProto(GroupMgmtProto.UpdateGroupReq proto) {
    return new UpdateGroupRequest(String.valueOf(proto.getGroupId()), proto.getName());
  }
}
