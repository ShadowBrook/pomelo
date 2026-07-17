package com.github.moxib.pomelo.service;

import com.github.moxib.pomelo.service.model.MessageRecord;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 基于 PostgreSQL 的 MessageRepository 实现。
 */
public class PgMessageRepository implements MessageRepository {

  private static final Logger LOG = LoggerFactory.getLogger(PgMessageRepository.class);

  private static final String SAVE_SQL = """
    INSERT INTO im_message_c2c (id, sender_id, recipient_id, msg_type, content, seq, status, created_at)
    VALUES ($1, $2, $3, $4, $5, $6, $7, $8) ON CONFLICT (id) DO NOTHING
    """;

  private static final String UPDATE_STATUS_SQL = """
    UPDATE im_message_c2c SET status = $1 WHERE id = $2 AND status < $1
    """;

  private static final String BATCH_UPDATE_STATUS_SQL = """
    UPDATE im_message_c2c SET status = $1 WHERE id = ANY($2) AND status < $1
    """;

  private static final String PULL_PENDING_SQL = """
    SELECT id, sender_id, recipient_id, msg_type, content, seq, status, created_at
    FROM im_message_c2c WHERE recipient_id = $1 AND seq > $2 AND status < 2 ORDER BY seq LIMIT $3
    """;

  private static final String FIND_BY_ID_SQL = """
    SELECT id, sender_id, recipient_id, msg_type, content, seq, status, created_at
    FROM im_message_c2c WHERE id = $1
    """;

  private static final String FIND_BY_IDS_SQL = """
    SELECT id, sender_id, recipient_id, msg_type, content, seq, status, created_at
    FROM im_message_c2c WHERE id = ANY($1)
    """;

  private final Pool pool;

  public PgMessageRepository(Vertx vertx) {
    PgConnectOptions opts = new PgConnectOptions()
      .setHost("localhost")
      .setPort(5432)
      .setDatabase("pomelo_db")
      .setUser("pomelo")
      .setPassword("pomelo123");
    this.pool = PgBuilder.pool()
      .connectingTo(opts)
      .using(vertx)
      .build();
    LOG.info("PgMessageRepository 已连接 PostgreSQL: {}:{}/{}",
      opts.getHost(), opts.getPort(), opts.getDatabase());
  }

  @Override
  public Future<Boolean> save(MessageRecord record) {
    return pool.preparedQuery(SAVE_SQL)
      .execute(Tuple.of(
        record.getId(),
        record.getSenderId(),
        record.getRecipientId(),
        record.getMsgType(),
        record.getContent(),
        record.getSeq(),
        record.getStatus(),
        record.getCreatedAt()))
      .map(r -> {
        int count = r.rowCount();
        boolean inserted = count > 0;
        if (inserted) {
          LOG.debug("C2C 消息持久化: id={} seq={}", record.getId(), record.getSeq());
        } else {
          LOG.debug("C2C 消息幂等跳过: id={}", record.getId());
        }
        return inserted;
      })
      .onFailure(e -> LOG.error("C2C 消息持久化失败 id={}: {}", record.getId(), e.getMessage()));
  }

  @Override
  public Future<Void> updateStatus(long messageId, int newStatus) {
    return pool.preparedQuery(UPDATE_STATUS_SQL)
      .execute(Tuple.of(newStatus, messageId))
      .onSuccess(r -> LOG.debug("消息状态更新: id={} status={}", messageId, newStatus))
      .onFailure(e -> LOG.error("消息状态更新失败 id={}: {}", messageId, e.getMessage()))
      .mapEmpty();
  }

  @Override
  public Future<Void> batchUpdateStatus(List<Long> messageIds, int newStatus) {
    if (messageIds == null || messageIds.isEmpty()) {
      return Future.succeededFuture();
    }
    List<Long> sorted = messageIds.stream().distinct().sorted().collect(Collectors.toList());
    return pool.preparedQuery(BATCH_UPDATE_STATUS_SQL)
      .execute(Tuple.tuple().addInteger(newStatus).addArrayOfLong(sorted.toArray(new Long[0])))
      .onSuccess(r -> LOG.debug("批量状态更新: count={} status={}", sorted.size(), newStatus))
      .onFailure(e -> LOG.error("批量状态更新失败: {}", e.getMessage()))
      .mapEmpty();
  }

  @Override
  public Future<List<MessageRecord>> pullPending(String userId, long sinceSeq, int limit) {
    return pool.preparedQuery(PULL_PENDING_SQL)
      .execute(Tuple.of(userId, sinceSeq, limit))
      .map(rows -> {
        List<MessageRecord> list = new ArrayList<>();
        for (Row row : rows) {
          list.add(rowToRecord(row));
        }
        return list;
      });
  }

  @Override
  public Future<MessageRecord> findById(long messageId) {
    return pool.preparedQuery(FIND_BY_ID_SQL)
      .execute(Tuple.of(messageId))
      .map(rows -> rows.size() > 0 ? rowToRecord(rows.iterator().next()) : null);
  }

  @Override
  public Future<List<MessageRecord>> findByIds(List<Long> messageIds) {
    if (messageIds == null || messageIds.isEmpty()) {
      return Future.succeededFuture(Collections.emptyList());
    }
    Long[] ids = messageIds.stream().distinct().sorted().toArray(Long[]::new);
    return pool.preparedQuery(FIND_BY_IDS_SQL)
      .execute(Tuple.tuple().addArrayOfLong(ids))
      .map(rows -> {
        List<MessageRecord> list = new ArrayList<>();
        for (Row row : rows) {
          list.add(rowToRecord(row));
        }
        return list;
      });
  }

  private MessageRecord rowToRecord(Row row) {
    return MessageRecord.builder()
      .id(row.getLong("id"))
      .senderId(row.getString("sender_id"))
      .recipientId(row.getString("recipient_id"))
      .msgType(row.getInteger("msg_type"))
      .content(row.getString("content"))
      .seq(row.getLong("seq"))
      .status(row.getInteger("status"))
      .createdAt(row.getLong("created_at"))
      .build();
  }

  public Future<Void> close() {
    return pool.close().onSuccess(v -> LOG.info("PgMessageRepository 已关闭"));
  }
}
