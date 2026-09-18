package com.github.moxib.pomelo.logic.model;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 通话会话（Redis 态，TTL=通话硬上限）。
 * state：0=ringing（振铃中） 1=active（接通）。
 * <p>
 * participants 为全部参与方（主叫在前）；为 null 表示旧格式会话（仅 caller/callee 两席），
 * 由 {@link #effectiveParticipants()} 统一兜底，兼容 Redis 中未过期的历史会话。
 * 被叫拒接即从 participants 移除（仅释放自己，通话对其余参与方继续）。
 */
public class CallSession {

  public static final int STATE_RINGING = 0;
  public static final int STATE_ACTIVE = 1;

  public String callId;
  public String room;
  public long callerId;
  public long calleeId;
  /** 群聊通话所属群（0=1:1 通话）：决定收尾时记录落群会话还是双方收件箱 */
  public long groupId;
  public List<Long> participants;
  /** 已接听的参与方（含迟到入会）；重复 accept 据此拒绝（重连走 CALL_TOKEN） */
  public List<Long> accepted = new ArrayList<>();
  public int mediaType;
  public int state;
  public long createdAt;
  public long answeredAt;

  public static CallSession ringing(String callId, String room, long callerId, List<Long> calleeIds,
                                    int mediaType, long now, long groupId) {
    CallSession s = new CallSession();
    s.callId = callId;
    s.room = room;
    s.callerId = callerId;
    s.calleeId = calleeIds.get(0);
    s.groupId = groupId;
    List<Long> members = new ArrayList<>();
    members.add(callerId);
    members.addAll(calleeIds);
    s.participants = members;
    s.mediaType = mediaType;
    s.state = STATE_RINGING;
    s.createdAt = now;
    return s;
  }

  public JsonObject toJson() {
    JsonObject o = new JsonObject()
      .put("callId", callId).put("room", room)
      .put("callerId", callerId).put("calleeId", calleeId)
      .put("groupId", groupId)
      .put("mediaType", mediaType).put("state", state)
      .put("createdAt", createdAt).put("answeredAt", answeredAt);
    if (participants != null) {
      o.put("participants", participants);
    }
    if (!accepted.isEmpty()) {
      o.put("accepted", accepted);
    }
    return o;
  }

  public static CallSession fromJson(JsonObject o) {
    CallSession s = new CallSession();
    s.callId = o.getString("callId");
    s.room = o.getString("room");
    s.callerId = o.getLong("callerId", 0L);
    s.calleeId = o.getLong("calleeId", 0L);
    s.groupId = o.getLong("groupId", 0L);
    JsonArray arr = o.getJsonArray("participants");
    if (arr != null) {
      List<Long> members = new ArrayList<>();
      for (Object v : arr) {
        members.add(((Number) v).longValue());
      }
      s.participants = members;
    }
    JsonArray acceptedArr = o.getJsonArray("accepted");
    if (acceptedArr != null) {
      for (Object v : acceptedArr) {
        s.accepted.add(((Number) v).longValue());
      }
    }
    s.mediaType = o.getInteger("mediaType", 0);
    s.state = o.getInteger("state", 0);
    s.createdAt = o.getLong("createdAt", 0L);
    s.answeredAt = o.getLong("answeredAt", 0L);
    return s;
  }

  /** 全部参与方（主叫在前），旧格式会话兜底为两席 */
  public List<Long> effectiveParticipants() {
    if (participants != null) {
      return participants;
    }
    return List.of(callerId, calleeId);
  }

  /** 该用户是否为通话参与方（含已被移出前的被叫） */
  public boolean involves(long userId) {
    return effectiveParticipants().contains(userId);
  }

  /** 该用户能否此刻接听：是被叫、未被移出且尚未接听过 */
  public boolean canJoin(long userId) {
    return userId != callerId && involves(userId) && !hasAccepted(userId);
  }

  public boolean hasAccepted(long userId) {
    return accepted.contains(userId);
  }

  /** 被叫列表（不含主叫） */
  public List<Long> callees() {
    return effectiveParticipants().stream().skip(1).toList();
  }

  /** 移出一个被叫（拒接释放） */
  public void removeParticipant(long userId) {
    if (participants != null) {
      participants.remove(Long.valueOf(userId));
    }
  }

  /** 事件推送的接收方：endedBy 的操作方本人不需要收到（自己发起的结束本地已知） */
  public List<Long> audienceOf(long endedBy) {
    return effectiveParticipants().stream().filter(id -> id != endedBy).toList();
  }

  /** 对端 userId（仅 1:1 语义使用） */
  public long peerOf(long userId) {
    return userId == callerId ? calleeId : callerId;
  }
}
