package com.github.moxib.pomelo.logic.model.requests;

import com.github.moxib.pomelo.proto.group.GroupMgmtProto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 创建群请求 DTO（PB CreateGroupReq / JSON 共用）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateGroupRequest(
  String name,
  String avatar
) {
  public static CreateGroupRequest fromProto(GroupMgmtProto.CreateGroupReq proto) {
    return new CreateGroupRequest(proto.getName(), proto.getAvatar());
  }
}
