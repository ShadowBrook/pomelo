package com.github.moxib.pomelo.logic.model.requests;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.github.moxib.pomelo.proto.group.GroupMgmtProto;

/**
 * 解散群聊请求 DTO（PB DissolveGroupReq / JSON 共用）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DissolveGroupRequest(
  String groupId
) {
  public static DissolveGroupRequest fromProto(GroupMgmtProto.DissolveGroupReq proto) {
    return new DissolveGroupRequest(String.valueOf(proto.getGroupId()));
  }
}
