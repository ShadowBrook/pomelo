package com.github.moxib.pomelo.logic.infrastructure;

import com.github.moxib.pomelo.logic.model.CallSession;
import io.vertx.core.Future;

/**
 * 通话运行态存储（Redis）。
 * 忙键用于忙线判定与会话权威性；room 反查键供 LiveKit webhook 兜底定位会话。
 */
public interface CallStateStore {

  /** 原子占位：该用户已在通话/振铃中返回 false（SET NX） */
  Future<Boolean> tryMarkBusy(long userId, String callId, long ttlMs);

  Future<Void> clearBusy(long userId);

  /** 该用户当前占用的 callId；无则 null */
  Future<String> getActiveCall(long userId);

  Future<Void> saveSession(CallSession session, long ttlMs);

  /** 不存在或已过期返回 null */
  Future<CallSession> getSession(String callId);

  Future<Void> deleteSession(String callId);

  Future<Void> bindRoom(String room, String callId, long ttlMs);

  /** room → callId；不存在返回 null */
  Future<String> resolveRoom(String room);
}
