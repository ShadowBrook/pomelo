package com.github.moxib.pomelo.logic.infrastructure;

import com.github.moxib.pomelo.logic.model.CallSession;
import io.vertx.core.Future;

import java.util.HashMap;
import java.util.Map;

/**
 * 内存实现的通话运行态存储（单测用）。语义对齐 Redis 版：
 * tryMarkBusy 为原子占位语义（同键第二次失败），其余为普通 map 读写。
 */
public class InMemoryCallStateStore implements CallStateStore {

  public final Map<Long, String> busy = new HashMap<>();
  public final Map<String, CallSession> sessions = new HashMap<>();
  public final Map<String, String> rooms = new HashMap<>();

  @Override
  public Future<Boolean> tryMarkBusy(long userId, String callId, long ttlMs) {
    return Future.succeededFuture(busy.putIfAbsent(userId, callId) == null);
  }

  @Override
  public Future<Void> clearBusy(long userId) {
    busy.remove(userId);
    return Future.succeededFuture();
  }

  @Override
  public Future<String> getActiveCall(long userId) {
    return Future.succeededFuture(busy.get(userId));
  }

  @Override
  public Future<Void> saveSession(CallSession session, long ttlMs) {
    sessions.put(session.callId, session);
    return Future.succeededFuture();
  }

  @Override
  public Future<CallSession> getSession(String callId) {
    return Future.succeededFuture(sessions.get(callId));
  }

  @Override
  public Future<Void> deleteSession(String callId) {
    sessions.remove(callId);
    return Future.succeededFuture();
  }

  @Override
  public Future<Void> bindRoom(String room, String callId, long ttlMs) {
    rooms.put(room, callId);
    return Future.succeededFuture();
  }

  @Override
  public Future<String> resolveRoom(String room) {
    return Future.succeededFuture(rooms.get(room));
  }
}
