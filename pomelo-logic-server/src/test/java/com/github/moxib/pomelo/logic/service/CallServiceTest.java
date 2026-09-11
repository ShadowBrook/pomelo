package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.logic.id.SnowflakeIdGenerator;
import com.github.moxib.pomelo.logic.infrastructure.CallRepository;
import com.github.moxib.pomelo.logic.infrastructure.GroupMemberContext;
import com.github.moxib.pomelo.logic.infrastructure.GroupMsgReader;
import com.github.moxib.pomelo.logic.infrastructure.GroupMsgWithSender;
import com.github.moxib.pomelo.logic.infrastructure.GroupRepository;
import com.github.moxib.pomelo.logic.infrastructure.InMemoryCallStateStore;
import com.github.moxib.pomelo.logic.infrastructure.RedisFactory;
import com.github.moxib.pomelo.logic.model.CallSession;
import com.github.moxib.pomelo.logic.model.GroupInfo;
import com.github.moxib.pomelo.logic.model.GroupMemberRecord;
import com.github.moxib.pomelo.model.PushEnvelope;
import com.github.moxib.pomelo.proto.call.CallProto;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
    rooms = new RecordingRoomManager();
    // 真实签发器（测试密钥经包私有构造器注入）：webhook 验签也依赖它
    tokens = new LiveKitTokenService("devkey", "test-secret-not-for-deployment", 900, "ws://lk:7880");
    pushRouter = new CapturingPushRouter();
    service = new CallService(vertx, pushRouter, friendsOf(CALLER, CALLEE),
      callRepo, store, tokens, rooms, new SnowflakeIdGenerator(1),
      RING_TIMEOUT, 3_600_000L);
  }

  // ------------------------------------------------------------------
  // fakes
  // ------------------------------------------------------------------

  /** 好友关系可配置：caller↔callee 互为好友，其余不是 */
  private static GroupRepository friendsOf(long a, long b) {
    return new GroupRepository() {
      @Override public Future<Boolean> isFriend(long x, long y) {
        return Future.succeededFuture((x == a && y == b) || (x == b && y == a));
      }
      @Override public Future<GroupInfo> findById(long id) { return Future.failedFuture(new UnsupportedOperationException()); }
      @Override public Future<java.util.List<GroupMsgWithSender>> pullMessages(long g, long c, int l, boolean bw) { return Future.failedFuture(new UnsupportedOperationException()); }
      @Override public Future<GroupMemberContext> getGroupMemberContext(long g, long u) { return Future.failedFuture(new UnsupportedOperationException()); }
      @Override public Future<Void> createGroup(GroupInfo group) { return Future.failedFuture(new UnsupportedOperationException()); }
      @Override public Future<java.util.List<GroupInfo>> findGroupsByUserId(long userId) { return Future.failedFuture(new UnsupportedOperationException()); }
      @Override public Future<java.util.List<GroupMemberRecord>> findMembers(long groupId) { return Future.failedFuture(new UnsupportedOperationException()); }
      @Override public Future<Void> addMember(long id, long groupId, long userId, int role, long now) { return Future.failedFuture(new UnsupportedOperationException()); }
      @Override public Future<Void> removeMember(long groupId, long userId) { return Future.failedFuture(new UnsupportedOperationException()); }
      @Override public Future<Boolean> isMember(long groupId, long userId) { return Future.failedFuture(new UnsupportedOperationException()); }
      @Override public Future<Map<Long, Long>> findMemberReadStates(long groupId) { return Future.failedFuture(new UnsupportedOperationException()); }
      @Override public Future<Boolean> isMuted(long groupId, long userId) { return Future.failedFuture(new UnsupportedOperationException()); }
      @Override public Future<Void> updateLastReadSeq(long groupId, long userId, long seq) { return Future.failedFuture(new UnsupportedOperationException()); }
      @Override public Future<Boolean> saveMessage(long id, long groupId, long sender, int msgType, String content, long seq, long createdAt, long clientMsgId) { return Future.failedFuture(new UnsupportedOperationException()); }
      @Override public Future<GroupMsgWithSender> findByGroupSenderAndClientMsgId(long groupId, long sender, long clientMsgId) { return Future.failedFuture(new UnsupportedOperationException()); }
      @Override public Future<java.util.List<GroupMsgReader>> findMsgReaders(long groupId, long seq) { return Future.failedFuture(new UnsupportedOperationException()); }
    };
  }

  private static class RecordingCallRepository implements CallRepository {
    int inserts, answered, ended;
    @Override public Future<Void> insert(long callId, String room, long callerId, long calleeId, int mediaType, long createdAt) {
      inserts++;
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

  private static class RecordingRoomManager implements CallRoomManager {
    final List<String> created = new ArrayList<>();
    final List<String> deleted = new ArrayList<>();
    boolean failCreate;
    @Override public Future<Void> createRoom(String room, int emptyTimeoutSeconds, int maxParticipants) {
      if (failCreate) {
        return Future.failedFuture("livekit down");
      }
      created.add(room);
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
      if (env.getTargetUserId().equals(String.valueOf(toUserId))) {
        try {
          return CallProto.CallEventPush.parseFrom(env.getBody());
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
    assertNotNull(store.rooms.get(store.sessions.get(callId).room));
    assertEquals(1, callRepo.inserts);
    assertEquals(CallSession.STATE_RINGING, store.sessions.get(callId).state);
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
  @DisplayName("END：通话中挂断 → 对端收到 hangup 推送")
  void hangupNotifiesPeer() throws Exception {
    String callId = invite();
    accept(callId, CALLEE);
    end(callId, CALLER);

    CallProto.CallEventPush push = lastPush(pushRouter, CALLEE);
    assertEquals(3, push.getEvent());
    assertEquals(END_REASON_HANGUP_VALUE, push.getReasonValue());
    assertEquals(1, callRepo.ended);
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
}
