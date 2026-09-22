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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 媒体归属校验在发送链路上真的生效（而不只是 MediaKeyGuard 单测通过）。
 */
@DisplayName("媒体归属校验接线")
class MediaOwnershipWiringTest {

  private static final String OWN_KEY = "image/100/20260910/0123456789abcdef0123456789abcdef.jpg";
  private static final String OTHER_KEY = "image/200/20260910/fedcba9876543210fedcba9876543210.jpg";

  private Vertx vertx;
  private final AtomicBoolean saved = new AtomicBoolean(false);

  @BeforeEach
  void setUp() {
    vertx = Vertx.vertx();
    saved.set(false);
  }

  @AfterEach
  void tearDown() {
    if (vertx != null) {
      vertx.close();
    }
  }

  private final MessageRepository msgRepo = new MessageRepository() {
    @Override public Future<Boolean> save(MessageRecord record) {
      saved.set(true);
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
  public Future<Integer> updateGroupInfo(long groupId, String name, String description) {
    return Future.succeededFuture(0);
  }

public Future<Boolean> isMember(long groupId, long userId) { return Future.succeededFuture(true); }
    @Override public Future<Map<Long, Long>> findMemberReadStates(long groupId) { return Future.succeededFuture(Map.of()); }
    @Override public Future<Boolean> isMuted(long groupId, long userId) { return Future.failedFuture(new UnsupportedOperationException()); }
    @Override public Future<Boolean> isFriend(long a, long b) { return Future.failedFuture(new UnsupportedOperationException()); }
    @Override public Future<Void> updateLastReadSeq(long groupId, long userId, long seq) { return Future.failedFuture(new UnsupportedOperationException()); }
    @Override public Future<Boolean> saveMessage(long id, long groupId, long sender, int msgType, String content, String ext, long seq, long createdAt, long clientMsgId) {
      saved.set(true);
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

  private static ImMessage c2cReq(String content) {
    return buildReq(CommonProto.Cmd.CMD_C2C_REQ_VALUE,
      ChatProto.C2CReq.newBuilder().setSenderId(100L).setRecipientId(200L)
        .setMessageId(1L).setMessage(media(content)).build().toByteArray());
  }

  private static ImMessage c2gReq(String content) {
    return buildReq(CommonProto.Cmd.CMD_C2G_REQ_VALUE,
      GroupProto.C2GReq.newBuilder().setGroupId(100L)
        .setMessageId(1L).setMessage(media(content)).build().toByteArray());
  }

  private static CommonProto.MessageContent media(String content) {
    return CommonProto.MessageContent.newBuilder()
      .setMsgTypeValue(CommonProto.MsgType.MSG_TYPE_IMAGE_VALUE)
      .setContent(ByteString.copyFromUtf8(content))
      .setTimestamp(System.currentTimeMillis()).build();
  }

  private static ImMessage buildReq(int cmd, byte[] body) {
    Map<String, String> headers = new HashMap<>();
    headers.put("userId", "100");
    return ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER).version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId((byte) 0).cmd(cmd).messageId("m-1").body(body).varHeaders(headers).build();
  }

  /** 失败响应是 ErrorBody，与业务 Resp 的 field 1 同为 code */
  private static int codeOf(ImMessage resp) throws Exception {
    return CommonProto.ErrorBody.parseFrom(resp.getBody()).getCode();
  }

  private ImMessage call(Future<ImMessage> f) throws Exception {
    return f.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  @Test
  @DisplayName("C2C：引用他人对象的消息被拒且不落库")
  void c2cRejectsForeignKey() throws Exception {
    C2CService service = new C2CService(noopPush(), msgRepo, seqClient(),
      new SnowflakeIdGenerator(1), (t, c) -> c);
    ImMessage resp = call(service.process(c2cReq("{\"key\":\"" + OTHER_KEY + "\"}")));
    assertNotEquals(0, codeOf(resp), "引用他人媒体对象必须被拒");
    assertFalse(saved.get(), "被拒的消息不得落库");
  }

  @Test
  @DisplayName("C2C：自己的对象正常发送")
  void c2cAcceptsOwnKey() throws Exception {
    C2CService service = new C2CService(noopPush(), msgRepo, seqClient(),
      new SnowflakeIdGenerator(1), (t, c) -> c);
    ImMessage resp = call(service.process(c2cReq("{\"key\":\"" + OWN_KEY + "\"}")));
    assertEquals(0, codeOf(resp));
    assertTrue(saved.get());
  }

  @Test
  @DisplayName("C2G：引用他人对象的消息被拒且不落库")
  void c2gRejectsForeignKey() throws Exception {
    C2GService service = new C2GService(vertx, noopPush(), groupRepo, seqClient(),
      new SnowflakeIdGenerator(1), (t, c) -> c);
    ImMessage resp = call(service.process(c2gReq("{\"key\":\"" + OTHER_KEY + "\"}")));
    assertNotEquals(0, codeOf(resp), "群消息同样不得引用他人媒体对象");
    assertFalse(saved.get(), "被拒的消息不得落库");
  }
}
