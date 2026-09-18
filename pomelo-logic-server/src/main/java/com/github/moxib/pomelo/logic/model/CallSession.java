package com.github.moxib.pomelo.logic.model;

import io.vertx.core.json.JsonObject;

/**
 * 通话会话（Redis 态，TTL=通话硬上限）。
 * state：0=ringing（振铃中） 1=active（接通）。
 */
public class CallSession {

  public static final int STATE_RINGING = 0;
  public static final int STATE_ACTIVE = 1;

  public String callId;
  public String room;
  public long callerId;
  public long calleeId;
  public int mediaType;
  public int state;
  public long createdAt;
  public long answeredAt;

  public static CallSession ringing(String callId, String room, long callerId, long calleeId,
                                    int mediaType, long now) {
    CallSession s = new CallSession();
    s.callId = callId;
    s.room = room;
    s.callerId = callerId;
    s.calleeId = calleeId;
    s.mediaType = mediaType;
    s.state = STATE_RINGING;
    s.createdAt = now;
    return s;
  }

  public JsonObject toJson() {
    return new JsonObject()
      .put("callId", callId).put("room", room)
      .put("callerId", callerId).put("calleeId", calleeId)
      .put("mediaType", mediaType).put("state", state)
      .put("createdAt", createdAt).put("answeredAt", answeredAt);
  }

  public static CallSession fromJson(JsonObject o) {
    CallSession s = new CallSession();
    s.callId = o.getString("callId");
    s.room = o.getString("room");
    s.callerId = o.getLong("callerId", 0L);
    s.calleeId = o.getLong("calleeId", 0L);
    s.mediaType = o.getInteger("mediaType", 0);
    s.state = o.getInteger("state", 0);
    s.createdAt = o.getLong("createdAt", 0L);
    s.answeredAt = o.getLong("answeredAt", 0L);
    return s;
  }

  /** 该用户是否为通话参与方（任意一方） */
  public boolean involves(long userId) {
    return userId == callerId || userId == calleeId;
  }

  /** 对端 userId */
  public long peerOf(long userId) {
    return userId == callerId ? calleeId : callerId;
  }
}
