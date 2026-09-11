package com.github.moxib.pomelo.logic.infrastructure;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * im_call 表读写。callId 字符串（snowflake-随机后缀）直接作主键文本，
 * 便于与 Redis 态、LiveKit 房间名互相印证。
 */
public class PgCallRepository implements CallRepository {

  private static final Logger LOG = LoggerFactory.getLogger(PgCallRepository.class);

  private static final String INSERT_SQL = """
    INSERT INTO im_call (id, room, caller_id, callee_id, media_type, state, created_at)
    VALUES ($1, $2, $3, $4, $5, 0, $6)
    """;

  private static final String ANSWER_SQL = """
    UPDATE im_call SET state = 1, answered_at = $2 WHERE id = $1
    """;

  private static final String END_SQL = """
    UPDATE im_call SET state = 2, ended_at = $2, end_reason = $3 WHERE id = $1
    """;

  private final Vertx vertx;

  public PgCallRepository(Vertx vertx) {
    this.vertx = vertx;
  }

  @Override
  public Future<Void> insert(long callId, String room, long callerId, long calleeId, int mediaType, long createdAt) {
    return PgPoolFactory.get(vertx).preparedQuery(INSERT_SQL)
      .execute(Tuple.of(String.valueOf(callId), room, callerId, calleeId, mediaType, createdAt))
      .<Void>mapEmpty()
      .onFailure(e -> LOG.error("通话记录写入失败 callId={}: {}", callId, e.getMessage()));
  }

  @Override
  public Future<Void> markAnswered(String callId, long answeredAt) {
    return PgPoolFactory.get(vertx).preparedQuery(ANSWER_SQL)
      .execute(Tuple.of(callId, answeredAt))
      .<Void>mapEmpty()
      .onFailure(e -> LOG.error("通话接听更新失败 callId={}: {}", callId, e.getMessage()));
  }

  @Override
  public Future<Void> markEnded(String callId, long endedAt, int endReason) {
    return PgPoolFactory.get(vertx).preparedQuery(END_SQL)
      .execute(Tuple.of(callId, endedAt, endReason))
      .<Void>mapEmpty()
      .onFailure(e -> LOG.error("通话结束更新失败 callId={}: {}", callId, e.getMessage()));
  }
}
