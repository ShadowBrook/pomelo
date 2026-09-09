package com.github.moxib.pomelo.logic.model.requests;

import com.github.moxib.pomelo.proto.pull.PullProto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 群消息拉取请求 DTO（PB PullGroupMsgReq / JSON 共用）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GroupPullMsgRequest(
  String groupId,
  long cursor,
  int limit,
  boolean isBackward
) {
  public static GroupPullMsgRequest fromProto(PullProto.PullGroupMsgReq proto) {
    return new GroupPullMsgRequest(
      String.valueOf(proto.getGroupId()),
      proto.getCursor(),
      proto.getLimit(),
      proto.getIsBackward());
  }
}
