package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.logic.id.SnowflakeIdGenerator;
import com.github.moxib.pomelo.logic.infrastructure.CallRepository;
import com.github.moxib.pomelo.logic.infrastructure.GroupMemberContext;
import com.github.moxib.pomelo.logic.infrastructure.GroupMsgReader;
import com.github.moxib.pomelo.logic.infrastructure.GroupMsgWithSender;
import com.github.moxib.pomelo.logic.infrastructure.GroupRepository;
import com.github.moxib.pomelo.logic.infrastructure.InMemoryCallStateStore;
import com.github.moxib.pomelo.logic.infrastructure.MessageRepository;
import com.github.moxib.pomelo.logic.infrastructure.RedisFactory;
import com.github.moxib.pomelo.logic.model.CallSession;
import com.github.moxib.pomelo.logic.model.GroupInfo;
import com.github.moxib.pomelo.logic.model.GroupMemberRecord;
import com.github.moxib.pomelo.logic.model.MessageRecord;
import com.github.moxib.pomelo.logic.model.UserIdInfo;
import com.github.moxib.pomelo.seqsvr.client.SeqClientService;
import com.github.moxib.pomelo.model.PushEnvelope;
import com.github.moxib.pomelo.proto.call.CallProto;
import com.github.moxib.pomelo.proto.chat.ChatProto;
import com.github.moxib.pomelo.proto.common.CommonProto;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.github.moxib.pomelo.proto.call.CallProto.CallEndReason.*;
import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 通话状态机测试：振铃/接听/拒绝/取消/超时/忙线/挂断/webhook 兜底。
 * 全部协作方为内存替身，不触 Redis/DB/LiveKit。
 */
@DisplayName("通话状态机")
class CallServiceTest {

  private static final long CALLER = 100L;
  private static final long CALLEE = 200L;
  private static final long STRANGER = 300L;
  private static final long RING_TIMEOUT = 80L;

  private static Vertx vertx;

  private InMemoryCallStateStore store;
  private RecordingCallRepository callRepo;
  private RecordingMessageRepository msgRepo;
  private RecordingRoomManager rooms;
  private CapturingPushRouter pushRouter;
  private LiveKitTokenService tokens;
  private CallService service;

  @BeforeAll
  static void setUpVertx() {
    vertx = Vertx.vertx();
  }

  @AfterAll
  static void tearDownVertx() {
    if (vertx != null) {
      vertx.close();
    }
  }

  @BeforeEach
  void setUp() {
    store = new InMemoryCallStateStore();
    callRepo = new RecordingCallRepository();
    msgRepo = new RecordingMessageRepository();
    rooms = new RecordingRoomManager();
    // 真实签发器（测试密钥经包私有构造器注入）：webhook 验签也依赖它
    tokens = new LiveKitTokenService("devkey", "test-secret-not-for-deployment", 900, "ws://lk:7880");
    pushRouter = new CapturingPushRouter();
    service = new CallService(vertx, pushRouter, groupRepoOf(CALLEE),
      callRepo, store, tokens, rooms, new SnowflakeIdGenerator(1),
      msgRepo, seqClient(), RING_TIMEOUT, 3_600_000L, 5);
  }

  private static SeqClientService seqClient() {
    return new SeqClientService(vertx) {
      @Override
      public Future<Long> fetchNextSequence(long id) {
        return Future.succeededFuture(1L);
      }
    };
  }

  // ------------------------------------------------------------------
  // fakes
  // ------------------------------------------------------------------

  private static final long TEST_GROUP = 9001L;

  /** 群仓储替身实例（测试内重指向，供断言群消息写入） */
  private RecordingGroupRepository groupRepoField;

  private RecordingGroupRepository groupRepoOf(long... bs) {
    RecordingGroupRepository repo = new RecordingGroupRepository();
    for (long b : bs) {
      repo.friends.add(b);
    }
    this.groupRepoField = repo;
    return repo;
  }

  /** 群仓储替身：好友/成员关系可配，群消息写入可断言（TEST_GROUP 的成员 = 主叫+被叫+300） */
  private static class RecordingGroupRepository implements GroupRepository {
    final java.util.Set<Long> friends = new java.util.HashSet<>();
    boolean callerMember = true;
    final List<String> savedContents = new ArrayList<>();
    final List<long[]> savedMeta = new ArrayList<>();
    final List<Long> memberIds = new ArrayList<>(List.of(CALLER, CALLEE, 300L));

    @Override public Future<Boolean> isFriend(long x, long y) {
      return Future.succeededFuture((x == CALLER && friends.contains(y)) || (y == CALLER && friends.contains(x)));
    }
    @Override public Future<Boolean> isMember(long groupId, long userId) {
      return Future.succeededFuture(groupId == TEST_GROUP && userId == CALLER && callerMember);
    }
    @Override public Future<Boolean> saveMessage(long id, long groupId, long sender, int msgType, String content, long seq, long createdAt, long clientMsgId) {
      savedMeta.add(new long[]{id, groupId, sender, msgType, clientMsgId});
      savedContents.add(content);
      return Future.succeededFuture(true);
    }
    @Override public Future<List<GroupMemberRecord>> findMembers(long groupId) {
      if (groupId != TEST_GROUP) {
        return Future.failedFuture(new UnsupportedOperationException());
      }
      List<GroupMemberRecord> members = new ArrayList<>();
      for (long uid : memberIds) {
        members.add(GroupMemberRecord.builder().groupId(groupId).userId(uid)
          .userName("user" + uid).nickname("昵称" + uid).role(0).joinedAt(0L).build());
      }
      return Future.succeededFuture(members);
    }
    @Override public Future<GroupInfo> findById(long id) { return Future.failedFuture(new UnsupportedOperationException()); }
    @Override public Future<java.util.List<GroupMsgWithSender>> pullMessages(long g, long c, int l, boolean bw) { return Future.failedFuture(new UnsupportedOperationException()); }
    @Override public Future<GroupMemberContext> getGroupMemberContext(long g, long u) { return Future.failedFuture(new UnsupportedOperationException()); }
    @Override public Future<Void> createGroup(GroupInfo group) { return Future.failedFuture(new UnsupportedOperationException()); }
    @Override public Future<java.util.List<GroupInfo>> findGroupsByUserId(long userId) { return Future.failedFuture(new UnsupportedOperationException()); }
    @Override public Future<Void> addMember(long id, long groupId, long userId, int role, long now) { return Future.failedFuture(new UnsupportedOperationException()); }
    @Override public Future<Void> removeMember(long groupId, long userId) { return Future.failedFuture(new UnsupportedOperationException()); }
    @Override public Future<Map<Long, Long>> findMemberReadStates(long groupId) { return Future.failedFuture(new UnsupportedOperationException()); }
    @Override public Future<Boolean> isMuted(long groupId, long userId) { return Future.failedFuture(new UnsupportedOperationException()); }
    @Override public Future<Void> updateLastReadSeq(long groupId, long userId, long seq) { return Future.failedFuture(new UnsupportedOperationException()); }
    @Override public Future<GroupMsgWithSender> findByGroupSenderAndClientMsgId(long groupId, long sender, long clientMsgId) { return Future.failedFuture(new UnsupportedOperationException()); }
    @Override public Future<java.util.List<GroupMsgReader>> findMsgReaders(long groupId, long seq) { return Future.failedFuture(new UnsupportedOperationException()); }
  }

  private static class RecordingCallRepository implements CallRepository {
    int inserts, answered, ended;
    final List<String> insertedParticipants = new ArrayList<>();
    @Override public Future<Void> insert(long callId, String room, long callerId, long calleeId, int mediaType,
                                         long createdAt, String participants) {
      inserts++;
      insertedParticipants.add(participants);
      return Future.succeededFuture();
    }
    @Override public Future<Void> markAnswered(String callId, long answeredAt) {
      answered++;
      return Future.succeededFuture();
    }
    @Override public Future<Void> markEnded(String callId, long endedAt, int endReason) {
      ended++;
      return Future.succeededFuture();
    }
  }

  /** 记录系统消息写入（通话记录） */
  private static class RecordingMessageRepository implements MessageRepository {
    final List<MessageRecord> saved = new ArrayList<>();
    @Override public Future<Boolean> save(MessageRecord record) {
      saved.add(record);
      return Future.succeededFuture(true);
    }
    @Override public Future<Void> batchUpdateStatus(long recipientId, List<Long> ids, int newStatus) { return Future.succeededFuture(); }
    @Override public Future<List<MessageRecord>> pullPending(long recipientId, long sinceSeq, int limit) { return Future.succeededFuture(List.of()); }
    @Override public Future<MessageRecord> findById(long messageId) { return Future.succeededFuture(null); }
    @Override public Future<List<MessageRecord>> findByIds(long recipientId, List<Long> ids) { return Future.succeededFuture(List.of()); }
    @Override public Future<MessageRecord> findBySenderAndClientMsgId(long senderId, long clientMsgId) { return Future.succeededFuture(null); }
    @Override public Future<List<MessageRecord>> pullConversation(String conversationId, long beforeTime, int limit) { return Future.succeededFuture(List.of()); }
    @Override public Future<Map<Long, UserIdInfo>> findUserIdsByIds(List<Long> ids) {
      Map<Long, UserIdInfo> info = new HashMap<>();
      for (long id : ids) {
        info.put(id, new UserIdInfo(String.valueOf(id), "user" + id, "昵称" + id));
      }
      return Future.succeededFuture(info);
    }
  }

  private static class RecordingRoomManager implements CallRoomManager {
    final List<String> created = new ArrayList<>();
    final List<Integer> createdCaps = new ArrayList<>();
    final List<String> deleted = new ArrayList<>();
    boolean failCreate;
    @Override public Future<Void> createRoom(String room, int emptyTimeoutSeconds, int maxParticipants) {
      if (failCreate) {
        return Future.failedFuture("livekit down");
      }
      created.add(room);
      createdCaps.add(maxParticipants);
      return Future.succeededFuture();
    }
    @Override public Future<Void> deleteRoom(String room) {
      deleted.add(room);
      return Future.succeededFuture();
    }
  }

  private static class CapturingPushRouter extends PushRouter {
    final List<PushEnvelope> pushes = new ArrayList<>();
    CapturingPushRouter() {
      super(vertx == null ? Vertx.vertx() : vertx);
    }
    @Override
    public void push(PushEnvelope env) {
      pushes.add(env);
    }
  }

  // ------------------------------------------------------------------
  // helpers
  // ------------------------------------------------------------------

  private static int await(io.vertx.core.Future<Integer> f) throws Exception {
    return f.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  private static ImMessage req(int cmd, com.google.protobuf.Message body, long userId) {
    Map<String, String> headers = new HashMap<>();
    headers.put("userId", String.valueOf(userId));
    return ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER).version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId((byte) 0).cmd(cmd).messageId("t-1")
      .body(body.toByteArray()).varHeaders(headers).build();
  }

  private static CallProto.CallEventPush lastPush(CapturingPushRouter router, long toUserId) {
    for (int i = router.pushes.size() - 1; i >= 0; i--) {
      PushEnvelope env = router.pushes.get(i);
      if (env.getCmd() == CMD_CALL_EVENT_PUSH_VALUE && env.getTargetUserId().equals(String.valueOf(toUserId))) {
        try {
          return CallProto.CallEventPush.parseFrom(env.getBody());
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    }
    return null;
  }

  /** 收件人最后一条通话记录 C2CNotify（MSG_TYPE_SYSTEM），无则 null */
  private static ChatProto.C2CNotify lastRecordNotify(CapturingPushRouter router, long toUserId) {
    for (int i = router.pushes.size() - 1; i >= 0; i--) {
      PushEnvelope env = router.pushes.get(i);
      if (env.getCmd() == CMD_C2C_NOTIFY_VALUE && env.getTargetUserId().equals(String.valueOf(toUserId))) {
        try {
          ChatProto.C2CNotify notify = ChatProto.C2CNotify.parseFrom(env.getBody());
          if (notify.getMessage().getMsgTypeValue() == CommonProto.MsgType.MSG_TYPE_SYSTEM_VALUE) {
            return notify;
          }
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    }
    return null;
  }

  /** 解 JWT payload（手签 HS256），断言 sub 与 room 用 */
  private static JsonObject jwtClaims(String jwt) {
    return new JsonObject(new String(
      java.util.Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), java.nio.charset.StandardCharsets.UTF_8));
  }

  /** 以测试 secret 构造合法的 LiveKit webhook 签名（payload 带 body sha256） */
  private String webhookToken(String roomName, String body) {
    long now = System.currentTimeMillis() / 1000;
    JsonObject claims = new JsonObject()
      .put("iss", "devkey")
      .put("exp", now + 300)
      .put("sha256", LiveKitTokenService.sha256Hex(body));
    String header = LiveKitTokenService.base64Url(new JsonObject().put("alg", "HS256").put("typ", "JWT").encode());
    String payloadB64 = LiveKitTokenService.base64Url(claims.encode());
    String signingInput = header + "." + payloadB64;
    return signingInput + "." + LiveKitTokenService.base64Url(
      LiveKitTokenService.hmacSha256("test-secret-not-for-deployment", signingInput));
  }

  private int respCode(ImMessage resp) throws Exception {
    return CallProto.CallInviteResp.parseFrom(resp.getBody()).getCode();
  }

  private String invite() throws Exception {
    ImMessage resp = service.process(req(CMD_CALL_INVITE_REQ_VALUE,
      CallProto.CallInviteReq.newBuilder()
        .setPeerId(CALLEE).setMediaType(CallProto.CallMediaType.CALL_MEDIA_VIDEO)
        .build(), CALLER)).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    assertEquals(0, respCode(resp));
    return CallProto.CallInviteResp.parseFrom(resp.getBody()).getCallId();
  }

  /** 群聊通话：重建 fields（被叫集合不同的好友关系），返回 INVITE 原始响应 */
  private ImMessage inviteGroupRaw(long... peerIds) throws Exception {
    List<Long> peers = Arrays.stream(peerIds).boxed().toList();
    store = new InMemoryCallStateStore();
    callRepo = new RecordingCallRepository();
    msgRepo = new RecordingMessageRepository();
    rooms = new RecordingRoomManager();
    pushRouter = new CapturingPushRouter();
    service = new CallService(vertx, pushRouter, groupRepoOf(peerIds),
      callRepo, store, tokens, rooms, new SnowflakeIdGenerator(1),
      msgRepo, seqClient(), RING_TIMEOUT, 3_600_000L, 5);
    return service.process(req(CMD_CALL_INVITE_REQ_VALUE,
      CallProto.CallInviteReq.newBuilder().addAllPeerIds(peers)
        .setGroupId(TEST_GROUP)
        .setMediaType(CallProto.CallMediaType.CALL_MEDIA_VIDEO)
        .build(), CALLER)).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  private ImMessage accept(String callId, long uid) throws Exception {
    return service.process(req(CMD_CALL_ACCEPT_REQ_VALUE,
      CallProto.CallAcceptReq.newBuilder().setCallId(callId).build(), uid))
      .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  private ImMessage end(String callId, long uid) throws Exception {
    return service.process(req(CMD_CALL_END_REQ_VALUE,
      CallProto.CallEndReq.newBuilder().setCallId(callId)
        .setReason(CallProto.CallEndReason.END_REASON_HANGUP).build(), uid))
      .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  // ------------------------------------------------------------------
  // tests
  // ------------------------------------------------------------------

  @Test
  @DisplayName("INVITE：被叫收到振铃推送（含主叫信息），双方占位、房间创建、记录落库")
  void inviteRingsCallee() throws Exception {
    String callId = invite();

    CallProto.CallEventPush ringing = lastPush(pushRouter, CALLEE);
    assertNotNull(ringing, "被叫应收到振铃推送");
    assertEquals(1, ringing.getEvent());
    assertEquals(CALLER, ringing.getPeerId());
    assertEquals(CallProto.CallMediaType.CALL_MEDIA_VIDEO_VALUE, ringing.getMediaTypeValue());

    assertEquals(callId, store.busy.get(CALLER));
    assertEquals(callId, store.busy.get(CALLEE));
    assertEquals(List.of(store.sessions.get(callId).room), rooms.created);
    assertEquals(List.of(5), rooms.createdCaps, "建房人数上限应取构造传入的配置值");
    assertNotNull(store.rooms.get(store.sessions.get(callId).room));
    assertEquals(1, callRepo.inserts);
    assertEquals(CallSession.STATE_RINGING, store.sessions.get(callId).state);
  }

  @Test
  @DisplayName("INVITE：房间人数上限钳到 ≥2（误配不影响 1:1 通话的第二席）")
  void inviteClampsParticipantCapToMinimumTwo() throws Exception {
    InMemoryCallStateStore store2 = new InMemoryCallStateStore();
    RecordingRoomManager rooms2 = new RecordingRoomManager();
    CallService tight = new CallService(vertx, new CapturingPushRouter(), groupRepoOf(CALLEE),
      new RecordingCallRepository(), store2, tokens, rooms2, new SnowflakeIdGenerator(2),
      new RecordingMessageRepository(), seqClient(), RING_TIMEOUT, 3_600_000L, 0);

    ImMessage resp = tight.process(req(CMD_CALL_INVITE_REQ_VALUE,
      CallProto.CallInviteReq.newBuilder().setPeerId(CALLEE)
        .setMediaType(CallProto.CallMediaType.CALL_MEDIA_AUDIO).build(), CALLER))
      .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

    assertEquals(0, respCode(resp));
    assertEquals(List.of(2), rooms2.createdCaps);
  }

  @Test
  @DisplayName("INVITE：非好友被拒")
  void inviteRejectsStranger() throws Exception {
    ImMessage resp = service.process(req(CMD_CALL_INVITE_REQ_VALUE,
      CallProto.CallInviteReq.newBuilder().setPeerId(STRANGER)
        .setMediaType(CallProto.CallMediaType.CALL_MEDIA_AUDIO).build(), CALLER))
      .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

    assertNotEquals(0, respCode(resp));
    assertTrue(pushRouter.pushes.isEmpty());
    assertEquals(0, rooms.created.size());
  }

  @Test
  @DisplayName("INVITE：被叫忙线 → 拒绝且不推送")
  void inviteRejectsBusyCallee() throws Exception {
    store.tryMarkBusy(CALLEE, "other-call", 60_000).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

    ImMessage resp = service.process(req(CMD_CALL_INVITE_REQ_VALUE,
      CallProto.CallInviteReq.newBuilder().setPeerId(CALLEE)
        .setMediaType(CallProto.CallMediaType.CALL_MEDIA_AUDIO).build(), CALLER))
      .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

    assertNotEquals(0, respCode(resp));
    assertNull(lastPush(pushRouter, CALLEE));
  }

  @Test
  @DisplayName("INVITE：LiveKit 建房失败 → 回滚忙键并返回错误")
  void inviteRollsBackWhenRoomCreationFails() throws Exception {
    rooms.failCreate = true;
    ImMessage resp = service.process(req(CMD_CALL_INVITE_REQ_VALUE,
      CallProto.CallInviteReq.newBuilder().setPeerId(CALLEE)
        .setMediaType(CallProto.CallMediaType.CALL_MEDIA_AUDIO).build(), CALLER))
      .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

    assertNotEquals(0, respCode(resp));
    assertFalse(store.busy.containsKey(CALLER), "主叫忙键应回滚");
    assertFalse(store.busy.containsKey(CALLEE), "被叫忙键应回滚");
  }

  @Test
  @DisplayName("ACCEPT：非被叫接听被拒（含主叫自己与陌生人）")
  void acceptRejectsNonCallee() throws Exception {
    String callId = invite();

    assertNotEquals(0, CallProto.CallAcceptResp.parseFrom(accept(callId, CALLER).getBody()).getCode());
    assertNotEquals(0, CallProto.CallAcceptResp.parseFrom(accept(callId, STRANGER).getBody()).getCode());
    assertEquals(CallSession.STATE_RINGING, store.sessions.get(callId).state);
  }

  @Test
  @DisplayName("ACCEPT：双方拿到同一房间的 token（resp 给被叫、push 给主叫），置为 active")
  void acceptIssuesBothTokens() throws Exception {
    String callId = invite();
    String room = store.sessions.get(callId).room;

    ImMessage resp = accept(callId, CALLEE);
    CallProto.CallAcceptResp accept = CallProto.CallAcceptResp.parseFrom(resp.getBody());
    assertEquals(0, accept.getCode());
    assertEquals(room, accept.getRoom());
    assertEquals("ws://lk:7880", accept.getWsUrl());
    assertEquals(String.valueOf(CALLEE), jwtClaims(accept.getToken()).getString("sub"), "resp 携带被叫的 token");
    assertEquals(room, jwtClaims(accept.getToken()).getJsonObject("video").getString("room"));

    CallProto.CallEventPush push = lastPush(pushRouter, CALLER);
    assertEquals(2, push.getEvent());
    assertEquals(room, push.getRoom());
    assertEquals(String.valueOf(CALLER), jwtClaims(push.getToken()).getString("sub"), "push 携带主叫的 token");

    assertEquals(CallSession.STATE_ACTIVE, store.sessions.get(callId).state);
    assertEquals(1, callRepo.answered);
  }

  @Test
  @DisplayName("END：振铃中被叫拒绝 → 主叫收到 rejected 推送，资源清理")
  void rejectEndsWithReason() throws Exception {
    String callId = invite();
    String room = store.sessions.get(callId).room;
    service.process(req(CMD_CALL_END_REQ_VALUE,
      CallProto.CallEndReq.newBuilder().setCallId(callId)
        .setReason(CallProto.CallEndReason.END_REASON_CANCEL).build(), CALLEE))
      .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

    CallProto.CallEventPush push = lastPush(pushRouter, CALLER);
    assertEquals(3, push.getEvent());
    assertEquals(END_REASON_REJECT_VALUE, push.getReasonValue(), "服务端按角色裁定：被叫=reject（忽略客户端上报的 cancel）");
    assertTrue(rooms.deleted.contains(room), "应请求删除房间");
    assertFalse(store.busy.containsKey(CALLER));
    assertFalse(store.busy.containsKey(CALLEE));
    assertNull(store.sessions.get(callId));
    assertEquals(1, callRepo.ended);
  }

  @Test
  @DisplayName("END：振铃中主叫取消 → 被叫收到 cancel 推送")
  void cancelNotifiesCallee() throws Exception {
    String callId = invite();
    service.process(req(CMD_CALL_END_REQ_VALUE,
      CallProto.CallEndReq.newBuilder().setCallId(callId)
        .setReason(CallProto.CallEndReason.END_REASON_HANGUP).build(), CALLER))
      .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

    CallProto.CallEventPush push = lastPush(pushRouter, CALLEE);
    assertEquals(3, push.getEvent());
    assertEquals(END_REASON_CANCEL_VALUE, push.getReasonValue());
  }

  @Test
  @DisplayName("END：非参与方无法拆散他人通话")
  void endRejectsOutsider() throws Exception {
    String callId = invite();
    ImMessage resp = end(callId, STRANGER);

    assertNotEquals(0, CallProto.CallEndResp.parseFrom(resp.getBody()).getCode());
    assertNotNull(store.sessions.get(callId), "会话不应被删除");
  }

  @Test
  @DisplayName("通话中用户主动挂断 → 发送 END(HANGUP) 并写入双方通话记录系统消息")
  void hangupNotifiesPeer() throws Exception {
    String callId = invite();
    accept(callId, CALLEE);
    end(callId, CALLER);

    CallProto.CallEventPush push = lastPush(pushRouter, CALLEE);
    assertEquals(3, push.getEvent());
    assertEquals(END_REASON_HANGUP_VALUE, push.getReasonValue());
    assertEquals(1, callRepo.ended);

    // 系统消息：双方收件箱各一条，MSG_TYPE_SYSTEM，内容含时长
    assertEquals(2, msgRepo.saved.size());
    for (MessageRecord record : msgRepo.saved) {
      assertEquals(CommonProto.MsgType.MSG_TYPE_SYSTEM_VALUE, record.getMsgType());
      assertTrue(record.getContent().contains("\"kind\":\"call\""));
      assertTrue(record.getContent().contains("\"answered\":true"));
    }
    // 一条发给主叫（sender=被叫），一条发给被叫（sender=主叫）
    assertTrue(msgRepo.saved.stream().anyMatch(r -> r.getSenderId() == CALLEE && r.getRecipientId() == CALLER));
    assertTrue(msgRepo.saved.stream().anyMatch(r -> r.getSenderId() == CALLER && r.getRecipientId() == CALLEE));
    // outgoing 相对收件人标记方向：主叫那份为 true、被叫那份为 false（客户端气泡落边依据）
    assertTrue(msgRepo.saved.stream().anyMatch(r -> r.getRecipientId() == CALLER
      && r.getContent().contains("\"outgoing\":true")));
    assertTrue(msgRepo.saved.stream().anyMatch(r -> r.getRecipientId() == CALLEE
      && r.getContent().contains("\"outgoing\":false")));
    // 实时通知带发送者 ext（接收方头像/预览不回退成数字 ID）
    ChatProto.C2CNotify recordToCallee = lastRecordNotify(pushRouter, CALLEE);
    assertNotNull(recordToCallee);
    assertEquals(CALLER, recordToCallee.getSenderId());
    assertEquals("user" + CALLER, recordToCallee.getMessage().getExtOrThrow("senderUserName"));
    assertEquals("昵称" + CALLER, recordToCallee.getMessage().getExtOrThrow("senderNickname"));
    ChatProto.C2CNotify recordToCaller = lastRecordNotify(pushRouter, CALLER);
    assertNotNull(recordToCaller);
    assertEquals("昵称" + CALLEE, recordToCaller.getMessage().getExtOrThrow("senderNickname"));
  }

  @Test
  @DisplayName("重复接听：第二次 accept 被拒（已接通）")
  void doubleAcceptRejected() throws Exception {
    String callId = invite();
    assertEquals(0, CallProto.CallAcceptResp.parseFrom(accept(callId, CALLEE).getBody()).getCode());
    assertNotEquals(0, CallProto.CallAcceptResp.parseFrom(accept(callId, CALLEE).getBody()).getCode());
  }

  @Test
  @DisplayName("振铃超时：系统结束并推送双方 timeout")
  void ringTimeoutEndsCall() throws Exception {
    String callId = invite();
    Thread.sleep(RING_TIMEOUT + 300);

    CallProto.CallEventPush toCaller = lastPush(pushRouter, CALLER);
    CallProto.CallEventPush toCallee = lastPush(pushRouter, CALLEE);
    assertEquals(END_REASON_TIMEOUT_VALUE, toCaller.getReasonValue());
    assertEquals(END_REASON_TIMEOUT_VALUE, toCallee.getReasonValue());
    assertFalse(store.busy.containsKey(CALLER));
    assertFalse(store.busy.containsKey(CALLEE));
    assertEquals(1, callRepo.ended);
  }

  @Test
  @DisplayName("TOKEN：接通后参与方可取新 token；振铃中不可")
  void tokenOnlyWhenActive() throws Exception {
    String callId = invite();
    ImMessage ringing = service.process(req(CMD_CALL_TOKEN_REQ_VALUE,
      CallProto.CallTokenReq.newBuilder().setCallId(callId).build(), CALLEE))
      .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    assertNotEquals(0, CallProto.CallTokenResp.parseFrom(ringing.getBody()).getCode());

    accept(callId, CALLEE);
    ImMessage active = service.process(req(CMD_CALL_TOKEN_REQ_VALUE,
      CallProto.CallTokenReq.newBuilder().setCallId(callId).build(), CALLER))
      .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    CallProto.CallTokenResp token = CallProto.CallTokenResp.parseFrom(active.getBody());
    assertEquals(0, token.getCode());
    assertEquals(store.sessions.get(callId).room, token.getRoom());
  }

  @Test
  @DisplayName("webhook 兜底：participant_left 触发对端掉线收尾")
  void webhookPeerDropEndsCall() throws Exception {
    String callId = invite();
    accept(callId, CALLEE);
    String room = store.rooms.keySet().iterator().next();
    String body = "{\"event\":\"participant_left\",\"room\":{\"name\":\"" + room + "\"}}";

    int status = await(service.onLiveKitWebhook(body, "Bearer " + webhookToken(room, body)));
    assertEquals(200, status);

    CallProto.CallEventPush push = lastPush(pushRouter, CALLER);
    assertEquals(3, push.getEvent());
    assertEquals(END_REASON_PEER_DROP_VALUE, push.getReasonValue());
    assertEquals(1, callRepo.ended);
  }

  @Test
  @DisplayName("webhook：验签失败返回 401，不影响通话")
  void webhookRejectsBadSignature() throws Exception {
    String callId = invite();
    int status = await(service.onLiveKitWebhook(
      "{\"event\":\"room_finished\",\"room\":{\"name\":\"call-x\"}}", "Bearer bogus.token.here"));
    assertEquals(401, status);
    assertNotNull(store.sessions.get(callId));
  }

  @Test
  @DisplayName("webhook：非通话房间返回 404")
  void webhookIgnoresForeignRooms() throws Exception {
    String body = "{\"event\":\"room_finished\",\"room\":{\"name\":\"someone-elses-room\"}}";
    int status = await(service.onLiveKitWebhook(body, "Bearer " + webhookToken("someone-elses-room", body)));
    assertEquals(404, status);
  }

  // ------------------------------------------------------------------
  // 群聊通话（多参与方）
  // ------------------------------------------------------------------

  @Test
  @DisplayName("群聊 INVITE：所有被叫收到振铃（含人数），全员占位，房间按配置上限创建")
  void groupInviteRingsAllCallees() throws Exception {
    ImMessage resp = inviteGroupRaw(CALLEE, 300L, 400L);
    assertEquals(0, respCode(resp));
    String callId = CallProto.CallInviteResp.parseFrom(resp.getBody()).getCallId();

    for (long callee : new long[]{CALLEE, 300L, 400L}) {
      CallProto.CallEventPush push = lastPush(pushRouter, callee);
      assertNotNull(push, "被叫 " + callee + " 应收到振铃");
      assertEquals(1, push.getEvent());
      assertEquals(4, push.getParticipantCount(), "振铃应携带含主叫的总人数");
      assertEquals(callId, store.busy.get(callee));
    }
    assertEquals(callId, store.busy.get(CALLER));
    assertEquals(List.of(100L, 200L, 300L, 400L), store.sessions.get(callId).effectiveParticipants());
    assertEquals(List.of(5), rooms.createdCaps, "房间人数上限取配置值");
    assertEquals(List.of("100,200,300,400"), callRepo.insertedParticipants, "参与方落库");
  }

  @Test
  @DisplayName("群聊 INVITE：超人数上限（5+主叫=6）被拒，不建房不推送")
  void groupInviteOverLimitRejected() throws Exception {
    ImMessage resp = inviteGroupRaw(CALLEE, 300L, 400L, 500L, 600L);

    assertNotEquals(0, respCode(resp));
    assertTrue(rooms.created.isEmpty());
    assertTrue(pushRouter.pushes.isEmpty());
  }

  @Test
  @DisplayName("群聊 INVITE：任一被叫忙线 → 整场失败，已占位的被叫全部回滚")
  void groupInviteAnyBusyFailsAll() throws Exception {
    // 手动重建 200/300 两位被叫的环境，并预占 300 的忙键（inviteGroupRaw 会重建 store）
    store = new InMemoryCallStateStore();
    callRepo = new RecordingCallRepository();
    msgRepo = new RecordingMessageRepository();
    rooms = new RecordingRoomManager();
    pushRouter = new CapturingPushRouter();
    service = new CallService(vertx, pushRouter, groupRepoOf(CALLEE, 300L),
      callRepo, store, tokens, rooms, new SnowflakeIdGenerator(1),
      msgRepo, seqClient(), RING_TIMEOUT, 3_600_000L, 5);
    store.tryMarkBusy(300L, "other-call", 60_000)
      .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

    ImMessage resp = service.process(req(CMD_CALL_INVITE_REQ_VALUE,
      CallProto.CallInviteReq.newBuilder().addAllPeerIds(List.of(CALLEE, 300L))
        .setMediaType(CallProto.CallMediaType.CALL_MEDIA_VIDEO).build(), CALLER))
      .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

    assertNotEquals(0, respCode(resp));
    assertFalse(store.busy.containsKey(CALLEE), "先占位的被叫应回滚");
    assertTrue(rooms.created.isEmpty());
    assertTrue(pushRouter.pushes.isEmpty());
  }

  @Test
  @DisplayName("群聊 INVITE：非群成员发起被拒")
  void groupInviteRejectsNonMember() throws Exception {
    store = new InMemoryCallStateStore();
    callRepo = new RecordingCallRepository();
    msgRepo = new RecordingMessageRepository();
    rooms = new RecordingRoomManager();
    pushRouter = new CapturingPushRouter();
    service = new CallService(vertx, pushRouter, groupRepoOf(CALLEE, 300L),
      callRepo, store, tokens, rooms, new SnowflakeIdGenerator(1),
      msgRepo, seqClient(), RING_TIMEOUT, 3_600_000L, 5);
    groupRepoField.callerMember = false;

    ImMessage resp = service.process(req(CMD_CALL_INVITE_REQ_VALUE,
      CallProto.CallInviteReq.newBuilder().addAllPeerIds(List.of(CALLEE, 300L))
        .setGroupId(TEST_GROUP)
        .setMediaType(CallProto.CallMediaType.CALL_MEDIA_VIDEO).build(), CALLER))
      .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

    assertNotEquals(0, respCode(resp));
    assertTrue(rooms.created.isEmpty());
    assertTrue(pushRouter.pushes.isEmpty());
  }

  @Test
  @DisplayName("群聊 ACCEPT：第二位被叫在接通后迟到入会，材料正常下发")
  void secondCalleeJoinsLate() throws Exception {
    ImMessage resp = inviteGroupRaw(CALLEE, 300L);
    String callId = CallProto.CallInviteResp.parseFrom(resp.getBody()).getCallId();
    assertEquals(0, CallProto.CallAcceptResp.parseFrom(accept(callId, CALLEE).getBody()).getCode());

    ImMessage late = accept(callId, 300L);
    CallProto.CallAcceptResp lateResp = CallProto.CallAcceptResp.parseFrom(late.getBody());
    assertEquals(0, lateResp.getCode());
    assertEquals(String.valueOf(300L), jwtClaims(lateResp.getToken()).getString("sub"), "迟到入会者拿到自己的 token");

    CallProto.CallEventPush push = lastPush(pushRouter, CALLER);
    assertEquals(2, push.getEvent());
    assertEquals(300L, push.getPeerId(), "ACCEPTED 推送携带新入会者");
    assertEquals(CallSession.STATE_ACTIVE, store.sessions.get(callId).state);
    assertEquals(1, callRepo.answered, "接通时间只在首位接听时落库");
  }

  @Test
  @DisplayName("群聊 END：部分被叫拒接 → 仅释放自己，通话对其余参与方继续")
  void rejectOneCalleeCallContinues() throws Exception {
    ImMessage resp = inviteGroupRaw(CALLEE, 300L);
    String callId = CallProto.CallInviteResp.parseFrom(resp.getBody()).getCallId();
    end(callId, CALLEE);

    CallSession session = store.sessions.get(callId);
    assertNotNull(session, "通话应继续");
    assertEquals(CallSession.STATE_RINGING, session.state);
    assertEquals(List.of(100L, 300L), session.effectiveParticipants(), "拒接者移出会话");
    assertFalse(store.busy.containsKey(CALLEE), "拒接者忙键释放");
    assertTrue(store.busy.containsKey(300L));
    assertEquals(0, callRepo.ended, "整场未结束不落结束态");
    assertTrue(msgRepo.saved.isEmpty(), "拒接不写通话记录");

    assertEquals(0, CallProto.CallAcceptResp.parseFrom(accept(callId, 300L).getBody()).getCode(), "其余被叫仍可接听");
  }

  @Test
  @DisplayName("群聊 END：全部被叫拒接 → 整场 REJECT 收尾，其余参与方各得记录")
  void rejectAllEndsWholeCall() throws Exception {
    ImMessage resp = inviteGroupRaw(CALLEE, 300L);
    String callId = CallProto.CallInviteResp.parseFrom(resp.getBody()).getCallId();
    end(callId, CALLEE);
    end(callId, 300L);

    assertNull(store.sessions.get(callId));
    assertFalse(store.busy.containsKey(CALLER));
    assertFalse(store.busy.containsKey(300L));
    CallProto.CallEventPush push = lastPush(pushRouter, CALLER);
    assertEquals(3, push.getEvent());
    assertEquals(END_REASON_REJECT_VALUE, push.getReasonValue());
    assertEquals(1, callRepo.ended);
    // 群聊通话记录只落群会话一条，不写双方收件箱
    assertEquals(1, groupRepoField.savedContents.size());
    assertTrue(msgRepo.saved.isEmpty());
  }

  @Test
  @DisplayName("群聊 END：任一参与方接通后挂断 → 整场结束，全员释放并各得记录")
  void hangupInGroupEndsForAll() throws Exception {
    ImMessage resp = inviteGroupRaw(CALLEE, 300L);
    String callId = CallProto.CallInviteResp.parseFrom(resp.getBody()).getCallId();
    accept(callId, CALLEE);
    accept(callId, 300L);
    end(callId, 300L);

    assertNull(store.sessions.get(callId));
    for (long uid : new long[]{CALLER, CALLEE, 300L}) {
      assertFalse(store.busy.containsKey(uid), uid + " 忙键应释放");
    }
    // 其余参与方各收到 ENDED，操作方字段=挂断者
    for (long uid : new long[]{CALLER, CALLEE}) {
      CallProto.CallEventPush push = lastPush(pushRouter, uid);
      assertEquals(3, push.getEvent());
      assertEquals(END_REASON_HANGUP_VALUE, push.getReasonValue());
      assertEquals(300L, push.getPeerId());
    }
    assertEquals(1, callRepo.ended);
    // 群记录：一条系统消息落群会话（sender=挂断者），不写任何收件箱
    assertEquals(1, groupRepoField.savedMeta.size());
    assertEquals(TEST_GROUP, groupRepoField.savedMeta.get(0)[1]);
    assertEquals(300L, groupRepoField.savedMeta.get(0)[2], "sender = 挂断发起者");
    assertTrue(groupRepoField.savedContents.get(0).contains("\"participants\":3"));
    assertTrue(groupRepoField.savedContents.get(0).contains("\"groupId\":" + TEST_GROUP));
    assertTrue(msgRepo.saved.isEmpty(), "群聊通话不写个人收件箱");
    // 在线成员（除挂断者本人）收到 C2G_NOTIFY
    long notified = pushRouter.pushes.stream()
      .filter(p -> p.getCmd() == CMD_C2G_NOTIFY_VALUE).count();
    assertEquals(2, notified, "主叫与另一被叫各一条群通知");
  }
}
