package com.github.moxib.pomelo.service.model.requests;

import com.github.moxib.pomelo.proto.relation.RelationProto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 好友操作请求 DTO（PB FriendAddReq/AcceptReq/DeleteReq + JSON 共用）。
 * 三种请求结构相同（userId + friendId），共用此 record。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FriendOpRequest(String userId, String friendId) {
  public static FriendOpRequest fromAddProto(RelationProto.FriendAddReq proto) {
    return new FriendOpRequest(proto.getUserId(), proto.getFriendId());
  }
  public static FriendOpRequest fromAcceptProto(RelationProto.FriendAcceptReq proto) {
    return new FriendOpRequest(proto.getUserId(), proto.getFriendId());
  }
  public static FriendOpRequest fromDeleteProto(RelationProto.FriendDeleteReq proto) {
    return new FriendOpRequest(proto.getUserId(), proto.getFriendId());
  }
}
