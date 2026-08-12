package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.config.SessionRouteTable;
import com.github.moxib.pomelo.logic.id.SnowflakeIdGenerator;
import com.github.moxib.pomelo.logic.infrastructure.MessageRepository;
import com.github.moxib.pomelo.logic.model.MessageRecord;
import com.github.moxib.pomelo.model.PushEnvelope;
import com.github.moxib.pomelo.proto.common.CommonProto;
import com.github.moxib.pomelo.seqsvr.client.SeqClientService;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C2CService 消息落库 seq 维度测试。
 * <p>
 * per-user 模型下，seq 是"收件人信箱"的同步版本号（写扩散），消息写进收件人信箱时
 * 应取 {@code fetchNextSequence(recipientId)}，而不是发送者的 seq。
 */
@DisplayName("C2CService seq 维度测试")
class C2CServiceTest {

  private Vertx vertx;

  @BeforeEach
  void setUp() {
    vertx = Vertx.vertx();
  }

  @AfterEach
  void tearDown() {
    if (vertx != null) {
      CountDownLatch latch = new CountDownLatch(1);
      vertx.close().onComplete(ar -> latch.countDown());
      try {
        latch.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** MessageRepository 最小 stub：save 返回 true，其余返回空 */
  private static MessageRepository stubRepo() {
    return new MessageRepository() {
      @Override public Future<Boolean> save(MessageRecord record) { return Future.succeededFuture(true); }
      @Override public Future<Void> updateStatus(long messageId, int newStatus) { return Future.succeededFuture(); }
      @Override public Future<Void> batchUpdateStatus(List<Long> messageIds, int newStatus) { return Future.succeededFuture(); }
      @Override public Future<List<MessageRecord>> pullPending(long recipientId, long sinceSeq, int limit) {
        return Future.succeededFuture(List.of());
      }
      @Override public Future<MessageRecord> findById(long messageId) { return Future.succeededFuture(null); }
      @Override public Future<List<MessageRecord>> findByIds(List<Long> messageIds) { return Future.succeededFuture(List.of()); }
      @Override public Future<List<MessageRecord>> pullConversation(String conversationId, long beforeTime, int limit) {
        return Future.succeededFuture(List.of());
      }
      @Override public Future<Map<Long, com.github.moxib.pomelo.logic.model.UserIdInfo>> findUserIdsByIds(List<Long> ids) {
        return Future.succeededFuture(Map.of());
      }
    };
  }

  @Test
  @DisplayName("消息落库 seq 取收件人 id（而非发送者）")
  void doSendAssignsRecipientSeq() throws Exception {
    // 捕获 seqsvr 发号器拿到的 id
    AtomicLong capturedSeqId = new AtomicLong(-1);
    SeqClientService seqClient = new SeqClientService(vertx) {
      @Override
      public Future<Long> fetchNextSequence(long id) {
        capturedSeqId.set(id);
        return Future.succeededFuture(42L);
      }
    };
    PushRouter noopPush = new PushRouter(vertx) {
      @Override
      public void push(PushEnvelope env) {
        // no-op
      }
    };

    C2CService service = new C2CService(noopPush, stubRepo(), seqClient,
      new SnowflakeIdGenerator(1), new SessionRouteTable(vertx));

    // 构造 JSON C2CReq：senderId=100, recipientId=200（数字 id，resolveId 直接 parse）
    byte[] body = new JsonObject()
      .put("senderId", "100")
      .put("recipientId", "200")
      .put("message", new JsonObject().put("msgType", 1).put("content", "hello"))
      .put("messageId", 1L)
      .put("timestamp", System.currentTimeMillis())
      .toBuffer().getBytes();
    ImMessage req = ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId((byte) 1)   // JSON
      .cmd(CommonProto.Cmd.CMD_C2C_REQ_VALUE)
      .messageId("m-1")
      .body(body)
      .varHeaders(new java.util.HashMap<>())
      .build();

    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<Throwable> err = new AtomicReference<>();
    service.process(req).onComplete(ar -> {
      if (ar.failed()) err.set(ar.cause());
      done.countDown();
    });
    assertTrue(done.await(10, TimeUnit.SECONDS), "process 应完成");
    assertNull(err.get(), "process 失败: " + err);

    assertEquals(200L, capturedSeqId.get(),
      "消息落库的 seq 应取收件人 id（recipientId），而非发送者 id（senderId）");
  }
}
