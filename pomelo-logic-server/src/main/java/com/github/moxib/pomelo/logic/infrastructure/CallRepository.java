package com.github.moxib.pomelo.logic.infrastructure;

import io.vertx.core.Future;

/**
 * 通话记录落库（im_call 表）。只记录事实，不做查询（通话记录页属后续迭代）。
 */
public interface CallRepository {

  Future<Void> insert(long callId, String room, long callerId, long calleeId, int mediaType, long createdAt);

  Future<Void> markAnswered(String callId, long answeredAt);

  Future<Void> markEnded(String callId, long endedAt, int endReason);
}
