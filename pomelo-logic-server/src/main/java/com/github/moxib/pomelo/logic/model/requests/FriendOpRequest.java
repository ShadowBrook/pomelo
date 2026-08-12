package com.github.moxib.pomelo.logic.model.requests;

import com.github.moxib.pomelo.proto.relation.RelationProto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 好友操作请求 DTO（PB FriendAddReq/AcceptReq/DeleteReq + JSON 共用）。
 * 三种请求结构相同（userId + friendId），共用此 record。
 * ID 字段为 String：JSON codec 中 snowflake 序列化为字符串（JS 安全），PB codec 从 int64 转换。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FriendOpRequest(String userId, String friendId) {
  public static FriendOpRequest fromAddProto(RelationProto.FriendAddReq proto) {
    return new FriendOpRequest(String.valueOf(proto.getUserId()), String.valueOf(proto.getFriendId()));
  }
  public static FriendOpRequest fromAcceptProto(RelationProto.FriendAcceptReq proto) {
    return new FriendOpRequest(String.valueOf(proto.getUserId()), String.valueOf(proto.getFriendId()));
  }
  public static FriendOpRequest fromDeleteProto(RelationProto.FriendDeleteReq proto) {
    return new FriendOpRequest(String.valueOf(proto.getUserId()), String.valueOf(proto.getFriendId()));
  }
}
