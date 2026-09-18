package com.github.moxib.pomelo.logic.infrastructure;

import io.vertx.core.Future;

/**
 * 通话记录落库（im_call 表）。只记录事实，不做查询（通话记录页属后续迭代）。
 */
public interface CallRepository {

  /**
   * @param participants 全部参与方 ID（逗号拼接，主叫在前；1:1 为两席）
   */
  Future<Void> insert(long callId, String room, long callerId, long calleeId, int mediaType,
                      long createdAt, String participants);

  Future<Void> markAnswered(String callId, long answeredAt);

  Future<Void> markEnded(String callId, long endedAt, int endReason);
}
