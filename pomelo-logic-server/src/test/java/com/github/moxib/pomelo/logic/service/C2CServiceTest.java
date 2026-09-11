package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.logic.id.SnowflakeIdGenerator;
import com.github.moxib.pomelo.logic.infrastructure.MessageRepository;
import com.github.moxib.pomelo.logic.model.MessageRecord;
import com.github.moxib.pomelo.logic.model.UserIdInfo;
import com.github.moxib.pomelo.model.PushEnvelope;
import com.github.moxib.pomelo.proto.chat.ChatProto;
import com.github.moxib.pomelo.proto.common.CommonProto;
import com.github.moxib.pomelo.seqsvr.client.SeqClientService;
import com.google.protobuf.ByteString;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
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
      @Override public Future<Void> batchUpdateStatus(long recipientId, List<Long> messageIds, int newStatus) { return Future.succeededFuture(); }
      @Override public Future<List<MessageRecord>> pullPending(long recipientId, long sinceSeq, int limit) {
        return Future.succeededFuture(List.of());
      }
      @Override public Future<MessageRecord> findById(long messageId) { return Future.succeededFuture(null); }
      @Override public Future<List<MessageRecord>> findByIds(long recipientId, List<Long> messageIds) { return Future.succeededFuture(List.of()); }
      @Override public Future<MessageRecord> findBySenderAndClientMsgId(long senderId, long clientMsgId) { return Future.succeededFuture(null); }
      @Override public Future<List<MessageRecord>> pullConversation(String conversationId, long beforeTime, int limit) {
        return Future.succeededFuture(List.of());
      }
      @Override public Future<Map<Long, UserIdInfo>> findUserIdsByIds(List<Long> ids) {
        return Future.succeededFuture(Map.of());
      }
    };
  }

  /** 构造 Protobuf C2CReq（codecId 冻结为 0） */
  private static ImMessage pbC2CReq(long senderId, long recipientId, int msgType, String content) {
    CommonProto.MessageContent message = CommonProto.MessageContent.newBuilder()
      .setMsgTypeValue(msgType)
      .setContent(ByteString.copyFromUtf8(content))
      .setTimestamp(System.currentTimeMillis())
      .build();
    byte[] body = ChatProto.C2CReq.newBuilder()
      .setSenderId(senderId)
      .setRecipientId(recipientId)
      .setMessageId(1L)
      .setMessage(message)
      .build()
      .toByteArray();
    return ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId((byte) 0)
      .cmd(CommonProto.Cmd.CMD_C2C_REQ_VALUE)
      .messageId("m-1")
      .body(body)
      .varHeaders(new HashMap<>())
      .build();
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
      new SnowflakeIdGenerator(1), (msgType, content) -> content);

    ImMessage req = pbC2CReq(100L, 200L, 1, "hello");

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

  @Test
  @DisplayName("媒体消息 notify 时 content 被签名")
  void imageNotifyContentIsSigned() throws Exception {
    CountDownLatch pushed = new CountDownLatch(1);
    AtomicReference<PushEnvelope> captured = new AtomicReference<>();
    PushRouter capturingPush = new PushRouter(vertx) {
      @Override
      public void push(PushEnvelope env) {
        captured.set(env);
        pushed.countDown();
      }
    };
    SeqClientService seqClient = new SeqClientService(vertx) {
      @Override
      public Future<Long> fetchNextSequence(long id) { return Future.succeededFuture(42L); }
    };
    C2CService service = new C2CService(capturingPush, stubRepo(), seqClient,
      new SnowflakeIdGenerator(1), (msgType, content) -> content + "?signed");

    // key 必须是服务端签发形态且归属发送者（见 MediaKeyGuard），否则消息在落库前就被拒
    ImMessage req = pbC2CReq(100L, 200L, 2,
      "{\"key\":\"image/100/20260910/0123456789abcdef0123456789abcdef.jpg\"}");

    CountDownLatch done = new CountDownLatch(1);
    service.process(req).onComplete(ar -> done.countDown());
    assertTrue(done.await(10, TimeUnit.SECONDS));
    assertTrue(pushed.await(10, TimeUnit.SECONDS), "notify 应被推送");

    ChatProto.C2CNotify notify = ChatProto.C2CNotify.parseFrom(captured.get().getBody());
    String content = notify.getMessage().getContent().toStringUtf8();
    assertTrue(content.endsWith("?signed"), "notify content 应被签名: " + content);
  }
}
