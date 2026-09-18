package com.github.moxib.pomelo.logic.infrastructure;

import com.github.moxib.pomelo.logic.model.CallSession;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;

/**
 * Redis 实现的通话运行态存储。
 * 键：call:busy:{userId}（占位）、call:session:{callId}（会话 JSON）、call:room:{room}（webhook 反查）。
 */
public class RedisCallStateStore implements CallStateStore {

  private final RedisFactory redis;

  public RedisCallStateStore(RedisFactory redis) {
    this.redis = redis;
  }

  @Override
  public Future<Boolean> tryMarkBusy(long userId, String callId, long ttlMs) {
    return send(Request.cmd(Command.SET)
      .arg("call:busy:" + userId).arg(callId).arg("NX").arg("PX").arg(ttlMs))
      .map(resp -> resp != null && "OK".equalsIgnoreCase(resp.toString()));
  }

  @Override
  public Future<Void> clearBusy(long userId) {
    return send(Request.cmd(Command.DEL).arg("call:busy:" + userId)).mapEmpty();
  }

  @Override
  public Future<String> getActiveCall(long userId) {
    return send(Request.cmd(Command.GET).arg("call:busy:" + userId))
      .map(resp -> resp == null ? null : resp.toString());
  }

  @Override
  public Future<Void> saveSession(CallSession session, long ttlMs) {
    return send(Request.cmd(Command.SET)
      .arg("call:session:" + session.callId).arg(session.toJson().encode()).arg("PX").arg(ttlMs))
      .mapEmpty();
  }

  @Override
  public Future<CallSession> getSession(String callId) {
    return send(Request.cmd(Command.GET).arg("call:session:" + callId))
      .map(resp -> resp == null ? null : CallSession.fromJson(new JsonObject(resp.toString())));
  }

  @Override
  public Future<Void> deleteSession(String callId) {
    return send(Request.cmd(Command.DEL).arg("call:session:" + callId)).mapEmpty();
  }

  @Override
  public Future<Void> bindRoom(String room, String callId, long ttlMs) {
    return send(Request.cmd(Command.SET)
      .arg("call:room:" + room).arg(callId).arg("PX").arg(ttlMs)).mapEmpty();
  }

  @Override
  public Future<String> resolveRoom(String room) {
    return send(Request.cmd(Command.GET).arg("call:room:" + room))
      .map(resp -> resp == null ? null : resp.toString());
  }

  private Future<Response> send(Request req) {
    var conn = redis.getConnection();
    if (conn == null) {
      return Future.failedFuture("Redis 未连接");
    }
    return conn.send(req);
  }
}
