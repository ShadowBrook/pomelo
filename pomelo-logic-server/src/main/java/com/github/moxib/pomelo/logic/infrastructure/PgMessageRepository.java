package com.github.moxib.pomelo.logic.infrastructure;

import com.github.moxib.pomelo.logic.model.MessageRecord;
import com.github.moxib.pomelo.logic.model.UserIdInfo;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 基于 PostgreSQL 的 MessageRepository 实现。
 * sender_id / recipient_id 使用 im_user.id (BIGINT)。
 */
public class PgMessageRepository implements MessageRepository {

  private static final Logger LOG = LoggerFactory.getLogger(PgMessageRepository.class);

  private static final String SAVE_SQL = """
    INSERT INTO im_message_c2c (id, sender_id, recipient_id, conversation_id, msg_type, content, seq, status, created_at)
    VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9) ON CONFLICT (id) DO NOTHING
    """;

  private static final String UPDATE_STATUS_SQL = """
    UPDATE im_message_c2c SET status = $1 WHERE id = $2 AND status < $1
    """;

  private static final String BATCH_UPDATE_STATUS_SQL = """
    UPDATE im_message_c2c SET status = $1 WHERE id = ANY($2) AND status < $1
    """;

  private static final String PULL_PENDING_SQL = """
    SELECT id, sender_id, recipient_id, conversation_id, msg_type, content, seq, status, created_at
    FROM im_message_c2c WHERE recipient_id = $1 AND seq > $2 AND status < 2 ORDER BY seq LIMIT $3
    """;

  private static final String FIND_BY_ID_SQL = """
    SELECT id, sender_id, recipient_id, conversation_id, msg_type, content, seq, status, created_at
    FROM im_message_c2c WHERE id = $1
    """;

  private static final String FIND_BY_IDS_SQL = """
    SELECT id, sender_id, recipient_id, conversation_id, msg_type, content, seq, status, created_at
    FROM im_message_c2c WHERE id = ANY($1)
    """;

  private static final String PULL_CONVERSATION_SQL = """
    SELECT id, sender_id, recipient_id, conversation_id, msg_type, content, seq, status, created_at
    FROM im_message_c2c WHERE conversation_id = $1 AND created_at < $2 ORDER BY created_at DESC LIMIT $3
    """;

  private static final String FIND_USER_ID_SQL = """
    SELECT id FROM im_user WHERE user_id = $1
    """;

  private static final String FIND_USER_IDS_BY_IDS_SQL = """
    SELECT id, user_id, user_name, nickname FROM im_user WHERE id = ANY($1)
    """;

  private final Pool pool;

  public PgMessageRepository(Vertx vertx) {
    this.pool = PgPoolFactory.get(vertx);
  }

  @Override
  public Future<Boolean> save(MessageRecord record) {
    return pool.preparedQuery(SAVE_SQL)
      .execute(Tuple.of(
        record.getId(),
        record.getSenderId(),
        record.getRecipientId(),
        record.getConversationId(),
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
  public Future<List<MessageRecord>> pullPending(long recipientId, long sinceSeq, int limit) {
    return pool.preparedQuery(PULL_PENDING_SQL)
      .execute(Tuple.of(recipientId, sinceSeq, limit))
      .map(rows -> {
        List<MessageRecord> list = new ArrayList<>();
        for (Row row : rows) list.add(rowToRecord(row));
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
        for (Row row : rows) list.add(rowToRecord(row));
        return list;
      });
  }

  @Override
  public Future<List<MessageRecord>> pullConversation(String conversationId, long beforeTime, int limit) {
    return pool.preparedQuery(PULL_CONVERSATION_SQL)
      .execute(Tuple.of(conversationId, beforeTime == 0 ? Long.MAX_VALUE : beforeTime, limit))
      .map(rows -> {
        List<MessageRecord> list = new ArrayList<>();
        for (Row row : rows) list.add(rowToRecord(row));
        return list;
      });
  }

  @Override
  public Future<Long> findUserId(String userId) {
    return pool.preparedQuery(FIND_USER_ID_SQL)
      .execute(Tuple.of(userId))
      .map(rows -> rows.size() > 0 ? rows.iterator().next().getLong("id") : 0L);
  }

  @Override
  public Future<Map<Long, UserIdInfo>> findUserIdsByIds(List<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return Future.succeededFuture(Collections.emptyMap());
    }
    Long[] idArray = ids.stream().distinct().toArray(Long[]::new);
    return pool.preparedQuery(FIND_USER_IDS_BY_IDS_SQL)
      .execute(Tuple.tuple().addArrayOfLong(idArray))
      .map(rows -> {
        Map<Long, UserIdInfo> result = new HashMap<>();
        for (Row row : rows) {
          result.put(row.getLong("id"), new UserIdInfo(
            row.getString("user_id"),
            row.getString("user_name"),
            row.getString("nickname")));
        }
        return result;
      });
  }

  private MessageRecord rowToRecord(Row row) {
    return MessageRecord.builder()
      .id(row.getLong("id"))
      .senderId(row.getLong("sender_id"))
      .recipientId(row.getLong("recipient_id"))
      .conversationId(row.getString("conversation_id"))
      .msgType(row.getInteger("msg_type"))
      .content(row.getString("content"))
      .seq(row.getLong("seq"))
      .status(row.getInteger("status"))
      .createdAt(row.getLong("created_at"))
      .build();
  }
}
