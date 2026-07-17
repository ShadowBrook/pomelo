package com.github.moxib.pomelo.gateway.handler;

import com.github.moxib.pomelo.common.ImMessage;
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
import java.util.List;

/**
 * 消息持久化仓库（PostgreSQL）。
 */
public class MessageRepository {

  private static final Logger LOG = LoggerFactory.getLogger(MessageRepository.class);

  private final Pool pool;

  public MessageRepository(Vertx vertx) {
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
    LOG.info("MessageRepository 已连接 PostgreSQL: {}:{}/{}",
      opts.getHost(), opts.getPort(), opts.getDatabase());
  }

  /** 保存单聊消息 */
  public Future<Void> saveC2CMessage(long id, String senderId, String recipientId,
                                     int msgType, String content, long seq, long createdAt) {
    return pool.preparedQuery(
        "INSERT INTO im_message_c2c (id, sender_id, recipient_id, msg_type, content, seq, status, created_at) " +
        "VALUES ($1, $2, $3, $4, $5, $6, 0, $7)")
      .execute(Tuple.of(id, senderId, recipientId, msgType, content, seq, createdAt))
      .onSuccess(r -> LOG.debug("C2C 消息持久化: id={}", id))
      .onFailure(e -> LOG.error("C2C 消息持久化失败 id={}: {}", id, e.getMessage()))
      .mapEmpty();
  }

  /** 拉取离线消息 */
  public Future<List<ImMessage>> pullOfflineMessages(String userId, long sinceSeq, int limit) {
    return pool.preparedQuery(
        "SELECT id, sender_id, recipient_id, msg_type, content, seq, status, created_at " +
        "FROM im_message_c2c WHERE recipient_id = $1 AND seq > $2 ORDER BY seq LIMIT $3")
      .execute(Tuple.of(userId, sinceSeq, limit))
      .map(rows -> {
        List<ImMessage> list = new ArrayList<>();
        for (Row row : rows) {
          list.add(ImMessage.builder()
            .magic(ImMessage.MAGIC_NUMBER)
            .version(ImMessage.WIRE_PROTOCOL_VERSION)
            .codecId((byte) 1)
            .cmd(0x0012)
            .messageId(String.valueOf(row.getLong("id")))
            .body(row.getString("content") != null
              ? row.getString("content").getBytes(java.nio.charset.StandardCharsets.UTF_8)
              : null)
            .build());
        }
        return list;
      });
  }

  public Future<Void> close() {
    return pool.close().onSuccess(v -> LOG.info("MessageRepository 已关闭"));
  }
}
