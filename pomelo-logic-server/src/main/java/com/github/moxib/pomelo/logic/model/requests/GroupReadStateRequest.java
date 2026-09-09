package com.github.moxib.pomelo.logic.model.requests;

import com.github.moxib.pomelo.proto.group.GroupMgmtProto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 群成员已读游标查询请求 DTO（PB / JSON 共用）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GroupReadStateRequest(String groupId) {

  public static GroupReadStateRequest fromProto(GroupMgmtProto.GetGroupReadStateReq proto) {
    return new GroupReadStateRequest(String.valueOf(proto.getGroupId()));
  }
}
