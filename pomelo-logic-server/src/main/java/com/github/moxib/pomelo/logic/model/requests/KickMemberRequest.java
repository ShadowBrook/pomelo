package com.github.moxib.pomelo.logic.model.requests;

import com.github.moxib.pomelo.proto.group.GroupMgmtProto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 移除群成员请求 DTO（PB KickMemberReq / JSON 共用）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KickMemberRequest(
  String groupId,
  String userId
) {
  public static KickMemberRequest fromProto(GroupMgmtProto.KickMemberReq proto) {
    return new KickMemberRequest(String.valueOf(proto.getGroupId()), String.valueOf(proto.getUserId()));
  }
}
