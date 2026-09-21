package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.config.ConfigHolder;
import com.github.moxib.pomelo.logic.infrastructure.CallRepository;
import com.github.moxib.pomelo.logic.infrastructure.CallStateStore;
import com.github.moxib.pomelo.logic.infrastructure.GroupRepository;
import com.github.moxib.pomelo.logic.infrastructure.MessageRepository;
import com.github.moxib.pomelo.logic.id.SnowflakeIdGenerator;
import com.github.moxib.pomelo.logic.model.CallSession;
import com.github.moxib.pomelo.logic.model.GroupMemberRecord;
import com.github.moxib.pomelo.logic.model.MessageRecord;
import com.github.moxib.pomelo.logic.model.requests.CallAcceptRequest;
import com.github.moxib.pomelo.logic.model.requests.CallEndRequest;
import com.github.moxib.pomelo.logic.model.requests.CallInviteRequest;
import com.github.moxib.pomelo.logic.model.requests.CallTokenRequest;
import com.github.moxib.pomelo.model.PushEnvelope;
import com.github.moxib.pomelo.proto.call.CallProto;
import com.github.moxib.pomelo.proto.chat.ChatProto;
import com.github.moxib.pomelo.proto.common.CommonProto;
import com.github.moxib.pomelo.proto.group.GroupProto;
import com.github.moxib.pomelo.seqsvr.client.SeqClientService;
import com.google.protobuf.ByteString;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.github.moxib.pomelo.proto.call.CallProto.CallEndReason.END_REASON_CANCEL_VALUE;
import static com.github.moxib.pomelo.proto.call.CallProto.CallEndReason.END_REASON_HANGUP_VALUE;
import static com.github.moxib.pomelo.proto.call.CallProto.CallEndReason.END_REASON_PEER_DROP_VALUE;
import static com.github.moxib.pomelo.proto.call.CallProto.CallEndReason.END_REASON_REJECT_VALUE;
import static com.github.moxib.pomelo.proto.call.CallProto.CallEndReason.END_REASON_TIMEOUT_VALUE;
import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.*;

/**
 * 1:1 / 多人（群聊）音视频通话状态机（idle → ringing → active → ended）。
 * <p>
 * 信令走 IM 通道（CALL_* 命令），媒体直连 LiveKit SFU：本服务只做
 * 资格校验、忙线判定、签发入会材料与生命周期收尾，不经手媒体。
 * 群聊通话即同一 LiveKit 房间多席：发起时带被叫列表，人数上限为
 * {@code call.maxParticipants}（建房参数与信令校验双重约束）。
 * <p>
 * 多人语义（v1，与 1:1 保持一致的简单模型）：
 * <ul>
 *   <li>任一被叫忙线 → 整场邀请失败并回滚全部占位</li>
 *   <li>首位接听即整场转 active，其余被叫可在通话中迟到入会</li>
 *   <li>振铃中被叫拒接 → 仅释放自己；全部被叫拒接才整场结束</li>
 *   <li>接通后任一参与方挂断 → 整场对全体结束（不掉线方经 ENDED 推送收尾）</li>
 * </ul>
 * 收尾三重保险：客户端显式 END（主路径）→ 振铃/通话上限定时器 →
 * {@link #onLiveKitWebhook}（进程被杀/断网未发 END 的兜底）。
 */
public class CallService extends ServiceBase {

  private static final Logger LOG = LoggerFactory.getLogger(CallService.class);

  /** CallEventPush.event 取值 */
  public static final int EVENT_RINGING = 1;
  public static final int EVENT_ACCEPTED = 2;
  public static final int EVENT_ENDED = 3;

  private final Vertx vertx;
  private final PushRouter pushRouter;
  private final GroupRepository groupRepo;
  private final CallRepository callRepo;
  private final CallStateStore store;
  private final CallTokenIssuer tokens;
  private final CallRoomManager rooms;
  private final SnowflakeIdGenerator snowflake;
  private final MessageRepository messageRepo;
  private final SeqClientService seqClient;

  private final long ringTimeoutMs;
  private final long maxDurationMs;
  private final int roomEmptyTimeoutSec;
  /** 单房间人数上限（LiveKit max_participants）；1:1 固定占 2 席，群聊通话在此阈值内放开 */
  private final int maxParticipants;

  /** callId → 振铃超时定时器（接听/结束时取消） */
  private final Map<String, Long> ringTimers = new ConcurrentHashMap<>();
  /** callId → 通话上限定时器（接通时挂） */
  private final Map<String, Long> maxTimers = new ConcurrentHashMap<>();

  private final SecureRandom random = new SecureRandom();

  /** 生产构造：超时参数来自配置（call.ringTimeoutMs / call.maxDurationMs） */
  public CallService(Vertx vertx, PushRouter pushRouter, GroupRepository groupRepo,
                     CallRepository callRepo, CallStateStore store,
                     CallTokenIssuer tokens, CallRoomManager rooms,
                     SnowflakeIdGenerator snowflake,
                     MessageRepository messageRepo, SeqClientService seqClient) {
    this(vertx, pushRouter, groupRepo, callRepo, store, tokens, rooms, snowflake,
      messageRepo, seqClient,
      ConfigHolder.getLong("call.ringTimeoutMs", 45_000L),
      ConfigHolder.getLong("call.maxDurationMs", 7_200_000L),
      ConfigHolder.getInt("call.maxParticipants", 5));
  }

  /** 测试/全参构造：人数上限在此钳到 ≥2（1:1 通话固定占 2 席，配小了会导致第二人无法入会） */
  public CallService(Vertx vertx, PushRouter pushRouter, GroupRepository groupRepo,
                     CallRepository callRepo, CallStateStore store,
                     CallTokenIssuer tokens, CallRoomManager rooms,
                     SnowflakeIdGenerator snowflake,
                     MessageRepository messageRepo, SeqClientService seqClient,
                     long ringTimeoutMs, long maxDurationMs, int maxParticipants) {
    this.vertx = vertx;
    this.pushRouter = pushRouter;
    this.groupRepo = groupRepo;
    this.callRepo = callRepo;
    this.store = store;
    this.tokens = tokens;
    this.rooms = rooms;
    this.snowflake = snowflake;
    this.messageRepo = messageRepo;
    this.seqClient = seqClient;
    this.ringTimeoutMs = ringTimeoutMs;
    this.maxDurationMs = maxDurationMs;
    this.maxParticipants = Math.max(2, maxParticipants);
    this.roomEmptyTimeoutSec = (int) Math.max(60, maxDurationMs / 1000);

    // webhook 由 ApiVerticle 经 EventBus 转入（{body, auth} 信封），回复值为 HTTP 状态码。
    // 泛型参数必须与发送端一致（JsonObject）：写成 <String> 时取值处抛
    // ClassCastException，webhook 会静默全废（LiveKit 反复重投）
    vertx.eventBus().<JsonObject>consumer("logic.call.webhook", msg -> {
      JsonObject envelope = msg.body();
      onLiveKitWebhook(envelope.getString("body"), envelope.getString("auth"))
        .onSuccess(msg::reply)
        .onFailure(e -> {
          LOG.error("webhook 处理失败", e);
          msg.reply(500);
        });
    });
  }

  public Future<ImMessage> process(ImMessage message) {
    int cmd = message.getCmd();
    try {
      String userId = getUserIdFromHeaders(message);
      if (userId == null || userId.isBlank()) {
        return Future.succeededFuture(buildErrorResp(message, respCmdFor(cmd), ErrorCode.UNAUTHORIZED, "未认证用户"));
      }
      long uid = Long.parseLong(userId);
      return switch (cmd) {
        case CMD_CALL_INVITE_REQ_VALUE -> invite(message, uid);
        case CMD_CALL_ACCEPT_REQ_VALUE -> accept(message, uid);
        case CMD_CALL_END_REQ_VALUE -> end(message, uid);
        case CMD_CALL_TOKEN_REQ_VALUE -> token(message, uid);
        default -> Future.succeededFuture(
          buildErrorResp(message, respCmdFor(cmd), ErrorCode.UNKNOWN_CMD, "未知通话命令"));
      };
    } catch (NumberFormatException e) {
      return Future.succeededFuture(buildErrorResp(message, respCmdFor(cmd), ErrorCode.BAD_REQUEST, "非法用户身份"));
    } catch (Exception e) {
      LOG.error("通话命令处理失败 cmd={}", cmd, e);
      return Future.succeededFuture(buildErrorResp(message, respCmdFor(cmd), ErrorCode.INTERNAL_ERROR, "通话服务异常"));
    }
  }

  // ------------------------------------------------------------------
  // INVITE
  // ------------------------------------------------------------------

  private Future<ImMessage> invite(ImMessage message, long callerId) {
    CallInviteRequest req = decode(message, CallInviteRequest.class);
    if (req.mediaType() != CallProto.CallMediaType.CALL_MEDIA_AUDIO_VALUE
      && req.mediaType() != CallProto.CallMediaType.CALL_MEDIA_VIDEO_VALUE) {
      return Future.succeededFuture(buildErrorResp(message, CMD_CALL_INVITE_RESP_VALUE, ErrorCode.BAD_REQUEST, "非法的媒体类型"));
    }
    // 被叫列表（去重保序）：peer_ids 优先，空则回退单被叫字段（1:1 兼容）
    List<Long> peers = normalizePeers(req, callerId);
    if (peers.isEmpty()) {
      return Future.succeededFuture(buildErrorResp(message, CMD_CALL_INVITE_RESP_VALUE, ErrorCode.BAD_REQUEST, "非法的被叫"));
    }
    if (peers.size() + 1 > maxParticipants) {
      return Future.succeededFuture(buildErrorResp(message, CMD_CALL_INVITE_RESP_VALUE,
        ErrorCode.BAD_REQUEST, "通话人数超限，最多支持 " + maxParticipants + " 人（含主叫）"));
    }
    final long groupId = req.groupId();
    if (groupId < 0) {
      return Future.succeededFuture(buildErrorResp(message, CMD_CALL_INVITE_RESP_VALUE,
        ErrorCode.BAD_REQUEST, "非法的群 ID"));
    }

    Future<Boolean> groupOk = groupId == 0
      ? Future.succeededFuture(Boolean.TRUE)
      : groupRepo.isMember(groupId, callerId);
    return groupOk.compose(isMember -> {
      if (!isMember) {
        return Future.succeededFuture(buildErrorResp(message, CMD_CALL_INVITE_RESP_VALUE,
          ErrorCode.UNAUTHORIZED, "仅群成员可发起群聊通话"));
      }
      return areAllFriends(callerId, peers).compose(allFriends -> {
      if (!allFriends) {
        return Future.succeededFuture(buildErrorResp(message, CMD_CALL_INVITE_RESP_VALUE,
          ErrorCode.UNAUTHORIZED, "仅支持好友之间通话"));
      }
      return store.getActiveCall(callerId).compose(callerBusy -> {
        if (callerBusy != null) {
          return Future.succeededFuture(buildErrorResp(message, CMD_CALL_INVITE_RESP_VALUE,
            ErrorCode.CONFLICT, "你已在通话中"));
        }
        // 被叫占位是忙线的唯一仲裁（SET NX 原子），任一忙线则整场失败；主叫随后占位
        long callIdNum = snowflake.nextId();
        String callId = String.valueOf(callIdNum);
        String room = "call-" + callId + "-" + randomHex16();
        long now = System.currentTimeMillis();

        return markCalleesBusy(peers, callId).compose(allFree -> {
          if (!allFree) {
            return Future.succeededFuture(buildErrorResp(message, CMD_CALL_INVITE_RESP_VALUE,
              ErrorCode.CONFLICT, "对方忙，请稍后再拨"));
          }
          return store.tryMarkBusy(callerId, callId, maxDurationMs).compose(callerClaimed -> {
            if (!callerClaimed) {
              return clearBusyAll(peers)
                .compose(v -> Future.succeededFuture(buildErrorResp(message, CMD_CALL_INVITE_RESP_VALUE,
                  ErrorCode.CONFLICT, "你已在通话中")));
            }
            CallSession session = CallSession.ringing(callId, room, callerId, peers,
              req.mediaType(), now, groupId);
            return rooms.createRoom(room, roomEmptyTimeoutSec, maxParticipants)
              .compose(v -> store.saveSession(session, maxDurationMs)
                .compose(v2 -> store.bindRoom(room, callId, maxDurationMs))
                .compose(v3 -> callRepo.insert(callIdNum, room, callerId, session.calleeId,
                  req.mediaType(), now, joinIds(session.effectiveParticipants()))))
              .compose(v -> {
                ringTimers.put(callId, vertx.setTimer(ringTimeoutMs,
                  t -> endBySystem(callId, END_REASON_TIMEOUT_VALUE)));
                pushRinging(session, message.getVarHeaders());
                LOG.info("通话振铃: callId={} caller={} callees={} media={}",
                  callId, callerId, peers, req.mediaType());
                return Future.succeededFuture(buildResponse(message, CMD_CALL_INVITE_RESP_VALUE,
                  CallProto.CallInviteResp.newBuilder()
                    .setCode(0).setMessage("success").setCallId(callId).build()));
              })
              .recover(err -> {
                LOG.error("建房/落库失败，回滚忙键 callId={}: {}", callId, err.getMessage());
                cleanupKeys(session);
                // 落库/写 Redis 失败时房间已经建出来了：本地删掉，否则空房间要挂到
                // empty_timeout（= 通话上限，最长 2 小时）才被回收
                deleteRoomQuietly(room);
                return Future.succeededFuture(buildErrorResp(message, CMD_CALL_INVITE_RESP_VALUE,
                  ErrorCode.INTERNAL_ERROR, "通话服务暂不可用"));
              });
          });
        });
      });
    });
    });
  }

  /** 被叫去重（保序），过滤非法值与自己；legacy 单被叫请求回退为单元素列表 */
  private static List<Long> normalizePeers(CallInviteRequest req, long callerId) {
    List<Long> raw = (req.peerIds() != null && !req.peerIds().isEmpty())
      ? req.peerIds() : List.of(req.peerId());
    LinkedHashSet<Long> deduped = new LinkedHashSet<>();
    for (Long p : raw) {
      if (p != null && p > 0 && p != callerId) {
        deduped.add(p);
      }
    }
    return new ArrayList<>(deduped);
  }

  private Future<Boolean> areAllFriends(long callerId, List<Long> peers) {
    Future<Boolean> f = Future.succeededFuture(Boolean.TRUE);
    for (Long peer : peers) {
      f = f.compose(ok -> ok
        ? groupRepo.isFriend(callerId, peer)
        : Future.succeededFuture(Boolean.FALSE));
    }
    return f;
  }

  /**
   * 依次占位全部被叫（SET NX 原子）。任一忙线：回滚已占位的并返回 false，
   * 保证「任一被叫忙线 → 整场邀请失败」的原子语义。
   */
  private Future<Boolean> markCalleesBusy(List<Long> peers, String callId) {
    List<Long> marked = new ArrayList<>();
    Future<Boolean> f = Future.succeededFuture(Boolean.TRUE);
    for (Long peer : peers) {
      f = f.compose(ok -> {
        if (!ok) {
          return Future.succeededFuture(Boolean.FALSE);
        }
        return store.tryMarkBusy(peer, callId, maxDurationMs).compose(claimed -> {
          if (claimed) {
            marked.add(peer);
            return Future.succeededFuture(Boolean.TRUE);
          }
          return clearBusyAll(marked).map(Boolean.FALSE);
        });
      });
    }
    return f;
  }

  private Future<Void> clearBusyAll(List<Long> userIds) {
    Future<Void> f = Future.succeededFuture();
    for (Long uid : userIds) {
      f = f.compose(v -> store.clearBusy(uid));
    }
    return f;
  }

  private void pushRinging(CallSession session, Map<String, String> headers) {
    String userName = headers != null ? headers.get("userName") : null;
    String nickname = headers != null ? headers.get("nickname") : null;
    CallProto.CallEventPush push = CallProto.CallEventPush.newBuilder()
      .setCallId(session.callId)
      .setEvent(EVENT_RINGING)
      .setMediaTypeValue(session.mediaType)
      .setPeerId(session.callerId)
      .setPeerUserName(userName != null ? userName : "")
      .setPeerNickname(nickname != null ? nickname : "")
      .setParticipantCount(session.effectiveParticipants().size())
      .build();
    for (Long callee : session.callees()) {
      pushRouter.push(new PushEnvelope(String.valueOf(callee), CMD_CALL_EVENT_PUSH_VALUE, push.toByteArray()));
    }
  }

  // ------------------------------------------------------------------
  // ACCEPT
  // ------------------------------------------------------------------

  private Future<ImMessage> accept(ImMessage message, long uid) {
    CallAcceptRequest req = decode(message, CallAcceptRequest.class);
    if (req.callId() == null || req.callId().isBlank()) {
      return Future.succeededFuture(buildErrorResp(message, CMD_CALL_ACCEPT_RESP_VALUE, ErrorCode.BAD_REQUEST, "callId 不能为空"));
    }
    return store.getSession(req.callId()).compose(session -> {
      // 身份只信 varHeader：主叫自己与未受邀者一律拒绝；被叫拒接后已从会话移除
      if (session == null || !session.canJoin(uid)) {
        return Future.succeededFuture(buildErrorResp(message, CMD_CALL_ACCEPT_RESP_VALUE,
          ErrorCode.NOT_FOUND, "通话不存在或已结束"));
      }
      boolean firstAccept = session.state == CallSession.STATE_RINGING;
      if (firstAccept) {
        Long ringTimer = ringTimers.remove(session.callId);
        if (ringTimer != null) {
          vertx.cancelTimer(ringTimer);
        }
        // 首位接听：整场转 active，通话上限定时器从此时起算
        session.state = CallSession.STATE_ACTIVE;
        session.answeredAt = System.currentTimeMillis();
        maxTimers.put(session.callId, vertx.setTimer(maxDurationMs,
          t -> endBySystem(session.callId, END_REASON_HANGUP_VALUE)));
      }
      session.accepted.add(uid);

      String myToken;
      String callerToken;
      try {
        myToken = tokens.issue(uid, session.room);
        callerToken = tokens.issue(session.callerId, session.room);
      } catch (IllegalStateException e) {
        return Future.succeededFuture(buildErrorResp(message, CMD_CALL_ACCEPT_RESP_VALUE,
          ErrorCode.INTERNAL_ERROR, "通话服务未配置：" + e.getMessage()));
      }

      // 每次接听都要持久化 accepted 列表；接通时间/落库只在首位接听时更新
      Future<Void> persist = store.saveSession(session, maxDurationMs)
        .compose(v -> firstAccept
          ? callRepo.markAnswered(session.callId, session.answeredAt)
          : Future.<Void>succeededFuture());

      return persist.compose(v -> {
        // resp 带接听者材料；主叫经 ACCEPTED 推送拿自己的材料（重复接听重复推，主叫已入会则忽略）
        CallProto.CallAcceptResp resp = CallProto.CallAcceptResp.newBuilder()
          .setCode(0).setMessage("success")
          .setRoom(session.room).setToken(myToken).setWsUrl(tokens.wsUrl())
          .build();
        // 推送带上接听者的名字：主叫方的参与方网格要显示是谁入了会
        Map<String, String> acceptHeaders = message.getVarHeaders();
        String joinerUserName = acceptHeaders != null ? acceptHeaders.get("userName") : null;
        String joinerNickname = acceptHeaders != null ? acceptHeaders.get("nickname") : null;
        CallProto.CallEventPush push = CallProto.CallEventPush.newBuilder()
          .setCallId(session.callId)
          .setEvent(EVENT_ACCEPTED)
          .setMediaTypeValue(session.mediaType)
          .setPeerId(uid)
          .setPeerUserName(joinerUserName != null ? joinerUserName : "")
          .setPeerNickname(joinerNickname != null ? joinerNickname : "")
          .setRoom(session.room).setToken(callerToken).setWsUrl(tokens.wsUrl())
          .build();
        pushRouter.push(new PushEnvelope(String.valueOf(session.callerId), CMD_CALL_EVENT_PUSH_VALUE, push.toByteArray()));
        LOG.info("通话接听: callId={} uid={} first={} participants={}",
          session.callId, uid, firstAccept, session.effectiveParticipants());
        return Future.succeededFuture(buildResponse(message, CMD_CALL_ACCEPT_RESP_VALUE, resp));
      });
    });
  }

  // ------------------------------------------------------------------
  // END
  // ------------------------------------------------------------------

  private Future<ImMessage> end(ImMessage message, long uid) {
    CallEndRequest req = decode(message, CallEndRequest.class);
    if (req.callId() == null || req.callId().isBlank()) {
      return Future.succeededFuture(buildErrorResp(message, CMD_CALL_END_RESP_VALUE, ErrorCode.BAD_REQUEST, "callId 不能为空"));
    }
    return store.getSession(req.callId()).compose(session -> {
      // 非参与方的结束请求直接拒绝（防止任何人拆散他人通话）
      if (session == null || !session.involves(uid)) {
        return Future.succeededFuture(buildErrorResp(message, CMD_CALL_END_RESP_VALUE,
          ErrorCode.NOT_FOUND, "通话不存在或已结束"));
      }
      ImMessage okResp = buildResponse(message, CMD_CALL_END_RESP_VALUE,
        CallProto.CallEndResp.newBuilder().setCode(0).setMessage("success").build());
      // 结束原因由服务端按「角色 × 状态」裁定，不信任客户端上报
      if (session.state == CallSession.STATE_RINGING) {
        if (uid == session.callerId) {
          return finishCall(session, END_REASON_CANCEL_VALUE, uid).map(v -> okResp);
        }
        // 振铃中被叫拒接：全部被叫都拒接才整场结束（记录/推送按整场收尾走）
        List<Long> remaining = session.callees().stream().filter(id -> id != uid).toList();
        if (!remaining.isEmpty()) {
          session.removeParticipant(uid);
          return store.clearBusy(uid)
            .compose(v -> store.saveSession(session, maxDurationMs))
            .map(v -> okResp);
        }
        return finishCall(session, END_REASON_REJECT_VALUE, uid).map(v -> okResp);
      }
      // 接通后任一参与方挂断 → 整场对全体结束
      return finishCall(session, END_REASON_HANGUP_VALUE, uid).map(v -> okResp);
    });
  }

  // ------------------------------------------------------------------
  // TOKEN（断线重连）
  // ------------------------------------------------------------------

  private Future<ImMessage> token(ImMessage message, long uid) {
    CallTokenRequest req = decode(message, CallTokenRequest.class);
    return store.getSession(req.callId()).compose(session -> {
      if (session == null || session.state != CallSession.STATE_ACTIVE || !session.involves(uid)) {
        return Future.succeededFuture(buildErrorResp(message, CMD_CALL_TOKEN_RESP_VALUE,
          ErrorCode.NOT_FOUND, "通话不存在或未接通"));
      }
      String token;
      try {
        token = tokens.issue(uid, session.room);
      } catch (IllegalStateException e) {
        return Future.succeededFuture(buildErrorResp(message, CMD_CALL_TOKEN_RESP_VALUE,
          ErrorCode.INTERNAL_ERROR, "通话服务未配置：" + e.getMessage()));
      }
      return Future.succeededFuture(buildResponse(message, CMD_CALL_TOKEN_RESP_VALUE,
        CallProto.CallTokenResp.newBuilder()
          .setCode(0).setMessage("success")
          .setRoom(session.room).setToken(token).setWsUrl(tokens.wsUrl())
          .build()));
    });
  }

  // ------------------------------------------------------------------
  // 收尾
  // ------------------------------------------------------------------

  /** 系统发起的结束（振铃超时 / 通话上限 / webhook 掉线） */
  private void endBySystem(String callId, int reason) {
    store.getSession(callId)
      .compose(session -> {
        if (session == null) {
          return Future.succeededFuture();
        }
        return finishCall(session, reason, 0);
      })
      .onFailure(e -> LOG.error("系统收尾失败 callId={}: {}", callId, e.getMessage()));
  }

  /**
   * 统一收尾：清定时器/键、删房间（尽力而为）、落库、通知其余参与方。
   *
   * @param endedBy 结束发起者 userId；0 表示系统
   */
  private Future<Void> finishCall(CallSession session, int reason, long endedBy) {
    cancelTimers(session.callId);
    cleanupKeys(session);
    long durationMs = session.answeredAt > 0 ? Math.max(0, System.currentTimeMillis() - session.answeredAt) : 0;
    LOG.info("通话结束: callId={} reason={} endedBy={} durationMs={} participants={}",
      session.callId, reason, endedBy, durationMs, session.effectiveParticipants());

    Future<Void> notify = notifyParticipants(session, reason, endedBy);
    Future<Void> records = session.groupId > 0
      ? writeGroupCallRecord(session, reason, durationMs, endedBy)
      : writeCallRecords(session, reason, durationMs);
    return Future.all(
      rooms.deleteRoom(session.room),
      callRepo.markEnded(session.callId, System.currentTimeMillis(), reason),
      notify,
      // 通话记录：1:1 写双方收件箱，群聊通话写群会话一条（尽力而为，失败不影响收尾）
      records.recover(err -> {
        LOG.warn("通话记录消息写入失败 callId={}: {}", session.callId, err.getMessage());
        return Future.succeededFuture();
      })
    ).mapEmpty();
  }

  /**
   * 通话记录落为各参与方收件箱各一条系统消息（MSG_TYPE_SYSTEM，内容为 JSON）：
   * 走正常的 seq/离线拉取/推送链路，刷新与跨设备都能在会话窗口看到记录。
   * 发起方那份 outgoing=true 落在自己一侧，其余参与者发起方视角落在对侧；
   * 群聊通话记录额外带 participants 数（前端据此展示「群聊通话」）。
   */
  private Future<Void> writeCallRecords(CallSession session, int reason, long durationMs) {
    boolean answered = session.answeredAt > 0;
    List<Long> members = session.effectiveParticipants();
    // 主叫自己的那份要以另一参与方作 sender（sender 永远不是收件人本人）
    long others = members.stream().filter(id -> id != session.callerId).findFirst().orElse(session.callerId);
    JsonObject base = new JsonObject()
      .put("kind", "call")
      .put("mediaType", session.mediaType)
      .put("answered", answered)
      .put("durationMs", durationMs)
      .put("reason", reason)
      .put("callId", session.callId)
      .put("participants", members.size());
    Future<Void> all = Future.succeededFuture();
    for (Long member : members) {
      boolean outgoing = member == session.callerId;
      long sender = outgoing ? others : session.callerId;
      all = all.compose(v -> writeCallRecord(sender, member,
        base.copy().put("outgoing", outgoing).encode()));
    }
    return all;
  }

  /**
   * 群聊通话记录：一条系统消息落群会话（im_group_message + C2GNotify 推在线成员），
   * 全群可见，不写双方收件箱。展示方 = 结束发起者（系统收尾时为主叫）；
   * 内容不带 outgoing（群里气泡落边按 senderId 判定）。
   */
  private Future<Void> writeGroupCallRecord(CallSession session, int reason, long durationMs, long endedBy) {
    long sender = endedBy != 0 ? endedBy : session.callerId;
    long id = snowflake.nextId();
    String content = new JsonObject()
      .put("kind", "call")
      .put("mediaType", session.mediaType)
      .put("answered", session.answeredAt > 0)
      .put("durationMs", durationMs)
      .put("reason", reason)
      .put("callId", session.callId)
      .put("participants", session.effectiveParticipants().size())
      .put("groupId", session.groupId)
      .encode();
    return seqClient.fetchNextSequence(session.groupId)
      .compose(seq -> {
        MessageRecord record = MessageRecord.builder()
          .id(id)
          .senderId(sender)
          .recipientId(session.groupId)
          .conversationId("g:" + session.groupId)
          .msgType(CommonProto.MsgType.MSG_TYPE_SYSTEM_VALUE)
          .content(content)
          .seq(seq)
          .status(0)
          .createdAt(System.currentTimeMillis())
          .clientMsgId(id)
          .build();
        return groupRepo.saveMessage(id, session.groupId, sender, record.getMsgType(), content, null, seq,
            record.getCreatedAt(), id)
          .compose(inserted -> inserted ? pushGroupCallRecordNotify(session, record) : Future.<Void>succeededFuture());
      });
  }

  /** 群记录的在线推送：C2GNotify 发给除记录方外的全部成员（离线由群拉取补齐） */
  private Future<Void> pushGroupCallRecordNotify(CallSession session, MessageRecord record) {
    return groupRepo.findMembers(session.groupId)
      .recover(err -> {
        LOG.warn("群通话记录推送失败（成员查询） groupId={}: {}", session.groupId, err.getMessage());
        return Future.succeededFuture(List.<GroupMemberRecord>of());
      })
      .compose(members -> messageRepo.findUserIdsByIds(List.of(record.getSenderId()))
        .recover(err -> Future.succeededFuture(Map.of()))
        .compose(idToInfo -> {
          var senderInfo = idToInfo.get(record.getSenderId());
          CommonProto.MessageContent.Builder mc = CommonProto.MessageContent.newBuilder()
            .setMsgTypeValue(record.getMsgType())
            .setContent(ByteString.copyFromUtf8(record.getContent() != null ? record.getContent() : ""))
            .setTimestamp(record.getCreatedAt());
          if (senderInfo != null && senderInfo.userName() != null && !senderInfo.userName().isEmpty()) {
            mc.putExt("senderUserName", senderInfo.userName());
          }
          if (senderInfo != null && senderInfo.nickname() != null && !senderInfo.nickname().isEmpty()) {
            mc.putExt("senderNickname", senderInfo.nickname());
          }
          byte[] body = GroupProto.C2GNotify.newBuilder()
            .setSenderId(record.getSenderId())
            .setGroupId(session.groupId)
            .setMessage(mc)
            .setSeq(record.getSeq())
            .setMessageId(record.getId())
            .build()
            .toByteArray();
          for (GroupMemberRecord member : members) {
            if (member.getUserId() == record.getSenderId()) {
              continue;
            }
            pushRouter.push(new PushEnvelope(String.valueOf(member.getUserId()),
              CMD_C2G_NOTIFY_VALUE, body));
          }
          return Future.<Void>succeededFuture();
        }));
  }

  private Future<Void> writeCallRecord(long from, long to, String content) {
    return seqClient.fetchNextSequence(to).compose(seq -> {
      long id = snowflake.nextId();
      MessageRecord record = MessageRecord.builder()
        .id(id)
        .senderId(from)
        .recipientId(to)
        .conversationId(MessageRecord.buildConversationId(from, to))
        .msgType(CommonProto.MsgType.MSG_TYPE_SYSTEM_VALUE)
        .content(content)
        .seq(seq)
        .status(0)
        .createdAt(System.currentTimeMillis())
        .clientMsgId(id)
        .build();
      return messageRepo.save(record)
        .compose(inserted -> inserted ? pushCallRecordNotify(record) : Future.<Void>succeededFuture());
    });
  }

  /**
   * 在线的一方经 C2CNotify 实时收到记录（离线则由拉取补齐）。
   * ext 补 senderUserName/senderNickname（与 C2CService/拉取路径一致），
   * 否则接收方气泡头像/预览会回退成数字 ID；查询失败降级为无 ext。
   */
  private Future<Void> pushCallRecordNotify(MessageRecord record) {
    return messageRepo.findUserIdsByIds(List.of(record.getSenderId()))
      .recover(err -> {
        LOG.warn("通话记录发送者信息查询失败 senderId={}: {}", record.getSenderId(), err.getMessage());
        return Future.succeededFuture(Map.of());
      })
      .compose(idToInfo -> {
        var senderInfo = idToInfo.get(record.getSenderId());
        CommonProto.MessageContent.Builder mc = CommonProto.MessageContent.newBuilder()
          .setMsgTypeValue(record.getMsgType())
          .setContent(ByteString.copyFromUtf8(record.getContent() != null ? record.getContent() : ""))
          .setTimestamp(record.getCreatedAt());
        if (senderInfo != null && senderInfo.userName() != null && !senderInfo.userName().isEmpty()) {
          mc.putExt("senderUserName", senderInfo.userName());
        }
        if (senderInfo != null && senderInfo.nickname() != null && !senderInfo.nickname().isEmpty()) {
          mc.putExt("senderNickname", senderInfo.nickname());
        }
        ChatProto.C2CNotify notify = ChatProto.C2CNotify.newBuilder()
          .setSenderId(record.getSenderId())
          .setRecipientId(record.getRecipientId())
          .setMessage(mc)
          .setSeq(record.getSeq())
          .setMessageId(record.getId())
          .build();
        pushRouter.push(new PushEnvelope(String.valueOf(record.getRecipientId()),
          CMD_C2C_NOTIFY_VALUE, notify.toByteArray()));
        return Future.<Void>succeededFuture();
      });
  }

  /** ENDED 推送给除操作方外的全部参与方；系统收尾（endedBy=0）推给所有人 */
  private Future<Void> notifyParticipants(CallSession session, int reason, long endedBy) {
    Future<Void> all = Future.succeededFuture();
    for (Long to : session.audienceOf(endedBy)) {
      all = all.compose(v -> pushEnded(session.callId, to, reason, endedBy));
    }
    return all;
  }

  private Future<Void> pushEnded(String callId, long toUserId, int reason, long peerId) {
    CallProto.CallEventPush push = CallProto.CallEventPush.newBuilder()
      .setCallId(callId)
      .setEvent(EVENT_ENDED)
      .setReasonValue(reason)
      .setPeerId(peerId)
      .build();
    pushRouter.push(new PushEnvelope(String.valueOf(toUserId), CMD_CALL_EVENT_PUSH_VALUE, push.toByteArray()));
    return Future.succeededFuture();
  }

  private void cleanupKeys(CallSession session) {
    store.deleteSession(session.callId);
    for (Long member : session.effectiveParticipants()) {
      store.clearBusy(member);
    }
  }

  /**
   * 尽力删房：失败只记日志（empty_timeout 兜底回收）。
   * 签发失败等异常是同步抛出的，必须就地挡住，别让回滚路径再炸一次。
   */
  private void deleteRoomQuietly(String room) {
    try {
      rooms.deleteRoom(room).onFailure(e -> LOG.warn("回滚删房失败 room={}: {}", room, e.getMessage()));
    } catch (Exception e) {
      LOG.warn("回滚删房异常 room={}: {}", room, e.getMessage());
    }
  }

  private static String joinIds(List<Long> ids) {
    StringBuilder sb = new StringBuilder();
    for (Long id : ids) {
      if (sb.length() > 0) {
        sb.append(',');
      }
      sb.append(id);
    }
    return sb.toString();
  }

  private void cancelTimers(String callId) {
    Long ring = ringTimers.remove(callId);
    if (ring != null) {
      vertx.cancelTimer(ring);
    }
    Long max = maxTimers.remove(callId);
    if (max != null) {
      vertx.cancelTimer(max);
    }
  }

  // ------------------------------------------------------------------
  // LiveKit webhook 兜底
  // ------------------------------------------------------------------

  /**
   * 处理 LiveKit webhook：验签后只关心 participant_left / room_finished。
   * 返回 HTTP 状态码（200=已处理，401=验签失败，404=非通话房间或会话已收尾）。
   * <p>
   * 注意：event/room 在 POST body 里，JWT payload 只承担验签（sha256）职责。
   * 任一参与方掉线即整场结束（v1 简单语义；本地主动挂断走 END 主路径不受影响）。
   */
  Future<Integer> onLiveKitWebhook(String rawBody, String authHeader) {
    if (!(tokens instanceof LiveKitTokenService real)) {
      LOG.warn("webhook 验签不可用（非真实签发器）");
      return Future.succeededFuture(503);
    }
    if (real.verifyWebhook(rawBody, authHeader) == null) {
      LOG.warn("LiveKit webhook 验签失败");
      return Future.succeededFuture(401);
    }
    JsonObject event;
    try {
      event = new JsonObject(rawBody);
    } catch (Exception e) {
      return Future.succeededFuture(404);
    }
    String eventName = event.getString("event", "");
    String roomName = event.getJsonObject("room", new JsonObject()).getString("name", "");
    if (!roomName.startsWith("call-")) {
      return Future.succeededFuture(404);
    }
    if (!"participant_left".equals(eventName) && !"room_finished".equals(eventName)) {
      return Future.succeededFuture(200);
    }
    return store.resolveRoom(roomName)
      .compose(callId -> callId == null ? Future.<CallSession>succeededFuture(null) : store.getSession(callId))
      .compose(session -> {
        if (session == null || session.state != CallSession.STATE_ACTIVE) {
          return Future.succeededFuture(404);
        }
        LOG.info("webhook 兜底结束通话: callId={} event={}", session.callId, eventName);
        endBySystem(session.callId, END_REASON_PEER_DROP_VALUE);
        return Future.succeededFuture(200);
      })
      .otherwise(err -> {
        LOG.error("webhook 处理异常: {}", err.getMessage());
        return 500;
      });
  }

  // ------------------------------------------------------------------

  private static int respCmdFor(int cmd) {
    return switch (cmd) {
      case CMD_CALL_INVITE_REQ_VALUE -> CMD_CALL_INVITE_RESP_VALUE;
      case CMD_CALL_ACCEPT_REQ_VALUE -> CMD_CALL_ACCEPT_RESP_VALUE;
      case CMD_CALL_END_REQ_VALUE -> CMD_CALL_END_RESP_VALUE;
      case CMD_CALL_TOKEN_REQ_VALUE -> CMD_CALL_TOKEN_RESP_VALUE;
      default -> CMD_ERROR_VALUE;
    };
  }

  private String randomHex16() {
    byte[] bytes = new byte[8];
    random.nextBytes(bytes);
    StringBuilder sb = new StringBuilder(16);
    for (byte b : bytes) {
      sb.append(Character.forDigit((b >> 4) & 0xF, 16));
      sb.append(Character.forDigit(b & 0xF, 16));
    }
    return sb.toString();
  }
}
