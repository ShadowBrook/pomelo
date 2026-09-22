package com.github.moxib.pomelo.logic.model.requests;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.github.moxib.pomelo.proto.group.GroupMgmtProto;

/**
 * 修改群信息请求 DTO（PB UpdateGroupReq / JSON 共用）。
 * 字段为 null 表示「本次不修改该字段」（proto3 optional 的 presence 语义）；
 * description 为空串表示「清空公告」。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateGroupRequest(
  String groupId,
  String name,
  String description
) {
  public static UpdateGroupRequest fromProto(GroupMgmtProto.UpdateGroupReq proto) {
    return new UpdateGroupRequest(
      String.valueOf(proto.getGroupId()),
      proto.hasName() ? proto.getName() : null,
      proto.hasDescription() ? proto.getDescription() : null);
  }
}
