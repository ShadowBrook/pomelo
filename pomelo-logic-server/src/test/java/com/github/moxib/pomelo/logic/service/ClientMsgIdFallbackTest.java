package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.logic.id.SnowflakeIdGenerator;
import com.github.moxib.pomelo.logic.infrastructure.GroupMemberContext;
import com.github.moxib.pomelo.logic.infrastructure.GroupMsgReader;
import com.github.moxib.pomelo.logic.infrastructure.GroupMsgWithSender;
import com.github.moxib.pomelo.logic.infrastructure.GroupRepository;
import com.github.moxib.pomelo.logic.infrastructure.MessageRepository;
import com.github.moxib.pomelo.logic.model.GroupInfo;
import com.github.moxib.pomelo.logic.model.GroupMemberRecord;
import com.github.moxib.pomelo.logic.model.MessageRecord;
import com.github.moxib.pomelo.logic.model.UserIdInfo;
import com.github.moxib.pomelo.model.PushEnvelope;
import com.github.moxib.pomelo.proto.chat.ChatProto;
import com.github.moxib.pomelo.proto.common.CommonProto;
import com.github.moxib.pomelo.proto.group.GroupProto;
import com.github.moxib.pomelo.seqsvr.client.SeqClientService;
import com.google.protobuf.ByteString;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等键（clientMsgId）兜底策略。
 * <p>
 * 客户端不提供数字 messageId 时（如压测客户端的 {@code "bench-<uuid>"}），
 * 兜底值曾用 {@code System.currentTimeMillis()}：同一发送者同一毫秒内的第二条消息
 * 会撞 {@code (sender_id, client_msg_id)} 唯一键被 {@code ON CONFLICT DO NOTHING} 吞掉，
 * 查询又返回第一条消息，客户端收到"成功"且不再重试——消息静默丢失。
 * 兜底必须换成进程内唯一的单调值（Snowflake）。
 */
@DisplayName("clientMsgId 兜底策略")
class ClientMsgIdFallbackTest {

  /** Snowflake 量级（~1e18）远大于墙钟毫秒（~1.7e12），据此区分兜底来源 */
  private static final long WALL_CLOCK_UPPER_BOUND = 1_000_000_000_000_000L;

  private Vertx vertx;
  private final List<Long> capturedIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    vertx = Vertx.vertx();
    capturedIds.clear();
  }

  @AfterEach
  void tearDown() {
    if (vertx != null) {
      vertx.close();
    }
  }

  private final MessageRepository repo = new MessageRepository() {
    @Override public Future<Boolean> save(MessageRecord record) {
      capturedIds.add(record.getClientMsgId());
      return Future.succeededFuture(true);
    }
    @Override public Future<Void> batchUpdateStatus(long recipientId, List<Long> ids, int newStatus) { return Future.succeededFuture(); }
    @Override public Future<List<MessageRecord>> pullPending(long recipientId, long sinceSeq, int limit) { return Future.succeededFuture(List.of()); }
    @Override public Future<MessageRecord> findById(long messageId) { return Future.succeededFuture(null); }
    @Override public Future<List<MessageRecord>> findByIds(long recipientId, List<Long> ids) { return Future.succeededFuture(List.of()); }
    @Override public Future<MessageRecord> findBySenderAndClientMsgId(long senderId, long clientMsgId) { return Future.succeededFuture(null); }
    @Override public Future<List<MessageRecord>> pullConversation(String cid, long before, int limit) { return Future.succeededFuture(List.of()); }
    @Override public Future<Map<Long, UserIdInfo>> findUserIdsByIds(List<Long> ids) { return Future.succeededFuture(Map.of()); }
  };

  private final GroupRepository groupRepo = new GroupRepository() {
    @Override public Future<GroupInfo> findById(long id) { return Future.succeededFuture(GroupInfo.builder().id(id).build()); }
    @Override public Future<List<GroupMsgWithSender>> pullMessages(long groupId, long cursor, int limit, boolean backward) { return Future.succeededFuture(List.of()); }
    @Override public Future<GroupMemberContext> getGroupMemberContext(long g, long u) {
      return Future.succeededFuture(new GroupMemberContext(true, "g", true, false));
    }
    @Override public Future<Void> createGroup(GroupInfo group) { return Future.failedFuture(new UnsupportedOperationException()); }
    @Override public Future<List<GroupInfo>> findGroupsByUserId(long userId) { return Future.failedFuture(new UnsupportedOperationException()); }
    @Override public Future<List<GroupMemberRecord>> findMembers(long groupId) { return Future.succeededFuture(List.of()); }
    @Override public Future<Void> addMember(long id, long groupId, long userId, int role, long now) { return Future.failedFuture(new UnsupportedOperationException()); }
    @Override public Future<Void> removeMember(long groupId, long userId) { return Future.failedFuture(new UnsupportedOperationException()); }
    @Override
  public Future<Integer> transferOwnership(long groupId, long fromOwnerId, long toUserId) {
    return Future.succeededFuture(0);
  }

  @Override
  public Future<Integer> dissolveGroup(long groupId, long ownerId) {
    return Future.succeededFuture(0);
  }

  @Override
  public Future<Integer> updateGroupName(long groupId, String name) {
    return Future.succeededFuture(0);
  }

public Future<Boolean> isMember(long groupId, long userId) { return Future.succeededFuture(true); }
    @Override public Future<Map<Long, Long>> findMemberReadStates(long groupId) { return Future.succeededFuture(Map.of()); }
    @Override public Future<Boolean> isMuted(long groupId, long userId) { return Future.failedFuture(new UnsupportedOperationException()); }
    @Override public Future<Boolean> isFriend(long a, long b) { return Future.failedFuture(new UnsupportedOperationException()); }
    @Override public Future<Void> updateLastReadSeq(long groupId, long userId, long seq) { return Future.failedFuture(new UnsupportedOperationException()); }
    @Override public Future<Boolean> saveMessage(long id, long groupId, long sender, int msgType, String content, String ext, long seq, long createdAt, long clientMsgId) {
      capturedIds.add(clientMsgId);
      return Future.succeededFuture(true);
    }
    @Override public Future<GroupMsgWithSender> findByGroupSenderAndClientMsgId(long groupId, long sender, long clientMsgId) { return Future.succeededFuture(null); }
    @Override public Future<List<GroupMsgReader>> findMsgReaders(long groupId, long seq) { return Future.failedFuture(new UnsupportedOperationException()); }
  };

  private PushRouter noopPush() {
    return new PushRouter(vertx) {
      @Override
      public void push(PushEnvelope env) {
        // no-op
      }
    };
  }

  private SeqClientService seqClient() {
    return new SeqClientService(vertx) {
      @Override
      public Future<Long> fetchNextSequence(long id) { return Future.succeededFuture(7L); }
    };
  }

  private C2CService c2cService() {
    return new C2CService(noopPush(), repo, seqClient(), new SnowflakeIdGenerator(1), (t, c) -> c);
  }

  private C2GService c2gService() {
    return new C2GService(vertx, noopPush(), groupRepo, seqClient(), new SnowflakeIdGenerator(1), (t, c) -> c);
  }

  private static ImMessage c2cReq(long bodyMessageId, String wireMessageId) {
    CommonProto.MessageContent message = CommonProto.MessageContent.newBuilder()
      .setMsgTypeValue(1).setContent(ByteString.copyFromUtf8("hi"))
      .setTimestamp(System.currentTimeMillis()).build();
    byte[] body = ChatProto.C2CReq.newBuilder()
      .setSenderId(100L).setRecipientId(200L).setMessageId(bodyMessageId).setMessage(message)
      .build().toByteArray();
    Map<String, String> headers = new HashMap<>();
    headers.put("userId", "100");
    return ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER).version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId((byte) 0).cmd(CommonProto.Cmd.CMD_C2C_REQ_VALUE)
      .messageId(wireMessageId).body(body).varHeaders(headers).build();
  }

  private static ImMessage c2gReq(long bodyMessageId, String wireMessageId) {
    CommonProto.MessageContent message = CommonProto.MessageContent.newBuilder()
      .setMsgTypeValue(1).setContent(ByteString.copyFromUtf8("hi"))
      .setTimestamp(System.currentTimeMillis()).build();
    byte[] body = GroupProto.C2GReq.newBuilder()
      .setGroupId(100L).setMessageId(bodyMessageId).setMessage(message)
      .build().toByteArray();
    Map<String, String> headers = new HashMap<>();
    headers.put("userId", "100");
    return ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER).version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId((byte) 0).cmd(CommonProto.Cmd.CMD_C2G_REQ_VALUE)
      .messageId(wireMessageId).body(body).varHeaders(headers).build();
  }

  private void await(Future<ImMessage> f) throws Exception {
    f.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  @Test
  @DisplayName("C2C：非数字 wire messageId 兜底为 Snowflake 而非墙钟毫秒")
  void c2cFallsBackToSnowflake() throws Exception {
    await(c2cService().process(c2cReq(0L, "bench-4f2a")));
    assertEquals(1, capturedIds.size());
    long id = capturedIds.get(0);
    assertTrue(id > WALL_CLOCK_UPPER_BOUND,
      "兜底值应是 Snowflake 量级，实际: " + id + "（墙钟毫秒约 1.7e12）");
  }

  @Test
  @DisplayName("C2C：同一毫秒内的连续兜底不重复")
  void c2cFallbackIdsAreUnique() throws Exception {
    C2CService service = c2cService();
    for (int i = 0; i < 5; i++) {
      await(service.process(c2cReq(0L, "bench-same")));
    }
    assertEquals(5, capturedIds.stream().distinct().count(),
      "相同 wire messageId 的并发/连发消息必须得到不同幂等键，否则会互相吞掉: " + capturedIds);
  }

  @Test
  @DisplayName("C2C：wire messageId 为 \"0\" 时也兜底")
  void c2cFallsBackWhenWireIdIsZero() throws Exception {
    await(c2cService().process(c2cReq(0L, "0")));
    assertTrue(capturedIds.get(0) > WALL_CLOCK_UPPER_BOUND,
      "clientMsgId 为 0 会让去重查询直接返回 null，响应携带从未落库的 id");
  }

  @Test
  @DisplayName("C2C：客户端提供的数字幂等键原样使用")
  void c2cKeepsClientProvidedId() throws Exception {
    await(c2cService().process(c2cReq(0L, "1735689600123")));
    assertEquals(1735689600123L, capturedIds.get(0));
  }

  @Test
  @DisplayName("C2G：非数字 wire messageId 兜底为 Snowflake 且不重复")
  void c2gFallsBackToSnowflake() throws Exception {
    C2GService service = c2gService();
    await(service.process(c2gReq(0L, "bench-4f2a")));
    await(service.process(c2gReq(0L, "bench-4f2a")));

    assertEquals(2, capturedIds.size());
    assertTrue(capturedIds.get(0) > WALL_CLOCK_UPPER_BOUND, "应为 Snowflake 量级");
    assertNotEquals(capturedIds.get(0), capturedIds.get(1), "两次兜底不得相同");
  }
}
