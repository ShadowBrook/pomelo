package com.github.moxib.pomelo.logic.model.requests;

import com.github.moxib.pomelo.proto.group.GroupMgmtProto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 查询群消息已读用户列表请求 DTO（PB GetGroupMsgReadStatusReq / JSON 共用）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GroupMsgReadRequest(
  String groupId,
  long seq
) {
  public static GroupMsgReadRequest fromProto(GroupMgmtProto.GetGroupMsgReadStatusReq proto) {
    return new GroupMsgReadRequest(String.valueOf(proto.getGroupId()), proto.getSeq());
  }
}
