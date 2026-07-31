package com.github.moxib.pomelo.logic.model.requests;

import com.github.moxib.pomelo.proto.relation.RelationProto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 用户搜索请求 DTO（PB SearchUserReq / JSON 共用）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SearchRequest(String keyword) {
  public static SearchRequest fromProto(RelationProto.SearchUserReq proto) {
    return new SearchRequest(proto.getKeyword());
  }
}
