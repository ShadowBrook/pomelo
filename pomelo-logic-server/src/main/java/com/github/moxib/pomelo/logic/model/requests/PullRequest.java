package com.github.moxib.pomelo.logic.model.requests;

import com.github.moxib.pomelo.proto.pull.PullProto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 消息拉取请求 DTO（PB PullReq / JSON 共用）。
 * userId 和 peerId 为 JSON 扩展字段。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PullRequest(
  long seq,
  int limit,
  String userId,
  String peerId
) {
  public static PullRequest fromProto(PullProto.PullReq proto) {
    return new PullRequest(proto.getSeq(), proto.getLimit(), null, null);
  }
}
