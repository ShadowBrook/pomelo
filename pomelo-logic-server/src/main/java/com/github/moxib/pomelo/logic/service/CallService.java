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
import com.github.moxib.pomelo.logic.model.MessageRecord;
import com.github.moxib.pomelo.logic.model.requests.CallAcceptRequest;
import com.github.moxib.pomelo.logic.model.requests.CallEndRequest;
import com.github.moxib.pomelo.logic.model.requests.CallInviteRequest;
import com.github.moxib.pomelo.logic.model.requests.CallTokenRequest;
import com.github.moxib.pomelo.model.PushEnvelope;
import com.github.moxib.pomelo.proto.call.CallProto;
import com.github.moxib.pomelo.proto.chat.ChatProto;
import com.github.moxib.pomelo.proto.common.CommonProto;
import com.github.moxib.pomelo.seqsvr.client.SeqClientService;
import com.google.protobuf.ByteString;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.github.moxib.pomelo.proto.call.CallProto.CallEndReason.END_REASON_CANCEL_VALUE;
import static com.github.moxib.pomelo.proto.call.CallProto.CallEndReason.END_REASON_HANGUP_VALUE;
import static com.github.moxib.pomelo.proto.call.CallProto.CallEndReason.END_REASON_PEER_DROP_VALUE;
import static com.github.moxib.pomelo.proto.call.CallProto.CallEndReason.END_REASON_REJECT_VALUE;
import static com.github.moxib.pomelo.proto.call.CallProto.CallEndReason.END_REASON_TIMEOUT_VALUE;
import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.*;

/**
 * 1:1 音视频通话状态机（idle → ringing → active → ended）。
 * <p>
 * 信令走 IM 通道（CALL_* 命令），媒体直连 LiveKit SFU：本服务只做
 * 资格校验、忙线判定、签发入会材料与生命周期收尾，不经手媒体。
 * <p>
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
      ConfigHolder.getLong("call.maxDurationMs", 7_200_000L));
  }

  /** 测试构造：显式超时参数 */
  public CallService(Vertx vertx, PushRouter pushRouter, GroupRepository groupRepo,
                     CallRepository callRepo, CallStateStore store,
                     CallTokenIssuer tokens, CallRoomManager rooms,
                     SnowflakeIdGenerator snowflake,
                     MessageRepository messageRepo, SeqClientService seqClient,
                     long ringTimeoutMs, long maxDurationMs) {
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
    this.roomEmptyTimeoutSec = (int) Math.max(60, maxDurationMs / 1000);

    // webhook 由 ApiVerticle 经 EventBus 转入（原始 body + Authorization 头），
    // 回复值为 HTTP 状态码
    vertx.eventBus().<String>consumer("logic.call.webhook", msg -> {
      JsonObject envelope = new JsonObject(msg.body());
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
    if (req.peerId() <= 0 || req.peerId() == callerId) {
      return Future.succeededFuture(buildErrorResp(message, CMD_CALL_INVITE_RESP_VALUE, ErrorCode.BAD_REQUEST, "非法的被叫"));
    }
    if (req.mediaType() != CallProto.CallMediaType.CALL_MEDIA_AUDIO_VALUE
      && req.mediaType() != CallProto.CallMediaType.CALL_MEDIA_VIDEO_VALUE) {
      return Future.succeededFuture(buildErrorResp(message, CMD_CALL_INVITE_RESP_VALUE, ErrorCode.BAD_REQUEST, "非法的媒体类型"));
    }

    return groupRepo.isFriend(callerId, req.peerId()).compose(isFriend -> {
      if (!isFriend) {
        return Future.succeededFuture(buildErrorResp(message, CMD_CALL_INVITE_RESP_VALUE,
          ErrorCode.UNAUTHORIZED, "仅支持好友之间通话"));
      }
      return store.getActiveCall(callerId).compose(callerBusy -> {
        if (callerBusy != null) {
          return Future.succeededFuture(buildErrorResp(message, CMD_CALL_INVITE_RESP_VALUE,
            ErrorCode.CONFLICT, "你已在通话中"));
        }
        // 被叫占位是忙线的唯一仲裁（SET NX 原子），主叫随后占位
        long callIdNum = snowflake.nextId();
        String callId = String.valueOf(callIdNum);
        String room = "call-" + callId + "-" + randomHex16();
        long now = System.currentTimeMillis();

        return store.tryMarkBusy(req.peerId(), callId, maxDurationMs).compose(calleeFree -> {
          if (!calleeFree) {
            return Future.succeededFuture(buildErrorResp(message, CMD_CALL_INVITE_RESP_VALUE,
              ErrorCode.CONFLICT, "对方忙，请稍后再拨"));
          }
          return store.tryMarkBusy(callerId, callId, maxDurationMs).compose(callerClaimed -> {
            if (!callerClaimed) {
              return store.clearBusy(req.peerId())
                .compose(v -> Future.succeededFuture(buildErrorResp(message, CMD_CALL_INVITE_RESP_VALUE,
                  ErrorCode.CONFLICT, "你已在通话中")));
            }
            CallSession session = CallSession.ringing(callId, room, callerId, req.peerId(),
              req.mediaType(), now);
            return rooms.createRoom(room, roomEmptyTimeoutSec, 2)
              .compose(v -> store.saveSession(session, maxDurationMs)
                .compose(v2 -> store.bindRoom(room, callId, maxDurationMs))
                .compose(v3 -> callRepo.insert(callIdNum, room, callerId, req.peerId(), req.mediaType(), now)))
              .compose(v -> {
                ringTimers.put(callId, vertx.setTimer(ringTimeoutMs,
                  t -> endBySystem(callId, END_REASON_TIMEOUT_VALUE)));
                pushRinging(session, message.getVarHeaders());
                LOG.info("通话振铃: callId={} caller={} callee={} media={}",
                  callId, callerId, req.peerId(), req.mediaType());
                return Future.succeededFuture(buildResponse(message, CMD_CALL_INVITE_RESP_VALUE,
                  CallProto.CallInviteResp.newBuilder()
                    .setCode(0).setMessage("success").setCallId(callId).build()));
              })
              .recover(err -> {
                LOG.error("建房/落库失败，回滚忙键 callId={}: {}", callId, err.getMessage());
                cleanupKeys(session);
                return Future.succeededFuture(buildErrorResp(message, CMD_CALL_INVITE_RESP_VALUE,
                  ErrorCode.INTERNAL_ERROR, "通话服务暂不可用"));
              });
          });
        });
      });
    });
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
      .build();
    pushRouter.push(new PushEnvelope(String.valueOf(session.calleeId), CMD_CALL_EVENT_PUSH_VALUE, push.toByteArray()));
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
      // 身份只信 varHeader：非被叫的接听（含主叫自己）一律拒绝
      if (session == null || uid != session.calleeId) {
        return Future.succeededFuture(buildErrorResp(message, CMD_CALL_ACCEPT_RESP_VALUE,
          ErrorCode.NOT_FOUND, "通话不存在或已结束"));
      }
      if (session.state != CallSession.STATE_RINGING) {
        return Future.succeededFuture(buildErrorResp(message, CMD_CALL_ACCEPT_RESP_VALUE,
          ErrorCode.CONFLICT, "通话已结束或已接通"));
      }
      Long ringTimer = ringTimers.remove(session.callId);
      if (ringTimer != null) {
        vertx.cancelTimer(ringTimer);
      }
      session.state = CallSession.STATE_ACTIVE;
      session.answeredAt = System.currentTimeMillis();

      String callerToken;
      String calleeToken;
      try {
        callerToken = tokens.issue(session.callerId, session.room);
        calleeToken = tokens.issue(session.calleeId, session.room);
      } catch (IllegalStateException e) {
        return Future.succeededFuture(buildErrorResp(message, CMD_CALL_ACCEPT_RESP_VALUE,
          ErrorCode.INTERNAL_ERROR, "通话服务未配置：" + e.getMessage()));
      }

      maxTimers.put(session.callId, vertx.setTimer(maxDurationMs,
        t -> endBySystem(session.callId, END_REASON_HANGUP_VALUE)));

      return store.saveSession(session, maxDurationMs)
        .compose(v -> callRepo.markAnswered(session.callId, session.answeredAt))
        .compose(v -> {
          // 一次交互下发双方材料：resp 带被叫的、push 带主叫的，省一次 CALL_TOKEN 往返
          CallProto.CallAcceptResp resp = CallProto.CallAcceptResp.newBuilder()
            .setCode(0).setMessage("success")
            .setRoom(session.room).setToken(calleeToken).setWsUrl(tokens.wsUrl())
            .build();
          CallProto.CallEventPush push = CallProto.CallEventPush.newBuilder()
            .setCallId(session.callId)
            .setEvent(EVENT_ACCEPTED)
            .setMediaTypeValue(session.mediaType)
            .setPeerId(session.calleeId)
            .setRoom(session.room).setToken(callerToken).setWsUrl(tokens.wsUrl())
            .build();
          pushRouter.push(new PushEnvelope(String.valueOf(session.callerId), CMD_CALL_EVENT_PUSH_VALUE, push.toByteArray()));
          LOG.info("通话接通: callId={} caller={} callee={}", session.callId, session.callerId, session.calleeId);
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
      // 结束原因由服务端按「角色 × 状态」裁定，不信任客户端上报
      int reason;
      if (session.state == CallSession.STATE_RINGING) {
        reason = uid == session.callerId ? END_REASON_CANCEL_VALUE : END_REASON_REJECT_VALUE;
      } else {
        reason = END_REASON_HANGUP_VALUE;
      }
      return finishCall(session, reason, uid)
        .map(v -> buildResponse(message, CMD_CALL_END_RESP_VALUE,
          CallProto.CallEndResp.newBuilder().setCode(0).setMessage("success").build()));
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
   * 统一收尾：清定时器/键、删房间（尽力而为）、落库、通知对端。
   *
   * @param endedBy 结束发起者 userId；0 表示系统
   */
  private Future<Void> finishCall(CallSession session, int reason, long endedBy) {
    cancelTimers(session.callId);
    cleanupKeys(session);
    long durationMs = session.answeredAt > 0 ? Math.max(0, System.currentTimeMillis() - session.answeredAt) : 0;
    LOG.info("通话结束: callId={} reason={} endedBy={} durationMs={}",
      session.callId, reason, endedBy, durationMs);

    Future<Void> notify = notifyPeer(session, reason, endedBy);
    return Future.all(
      rooms.deleteRoom(session.room),
      callRepo.markEnded(session.callId, System.currentTimeMillis(), reason),
      notify,
      // 通话记录以系统消息形式写入双方收件箱（尽力而为，失败不影响收尾）
      writeCallRecords(session, reason, durationMs).recover(err -> {
        LOG.warn("通话记录消息写入失败 callId={}: {}", session.callId, err.getMessage());
        return Future.succeededFuture();
      })
    ).mapEmpty();
  }

  /**
   * 通话记录落为双方收件箱各一条系统消息（MSG_TYPE_SYSTEM，内容为 JSON）：
   * 走正常的 seq/离线拉取/推送链路，刷新与跨设备都能在会话窗口看到记录。
   */
  private Future<Void> writeCallRecords(CallSession session, int reason, long durationMs) {
    boolean answered = session.answeredAt > 0;
    String content = new JsonObject()
      .put("kind", "call")
      .put("mediaType", session.mediaType)
      .put("answered", answered)
      .put("durationMs", durationMs)
      .put("reason", reason)
      .put("callId", session.callId)
      .encode();
    return writeCallRecord(session.callerId, session.calleeId, content)
      .compose(v -> writeCallRecord(session.calleeId, session.callerId, content))
      .mapEmpty();
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
        .compose(inserted -> {
          if (inserted) {
            pushCallRecordNotify(record);
          }
          return Future.<Void>succeededFuture();
        });
    });
  }

  /** 在线的一方经 C2CNotify 实时收到记录（离线则由拉取补齐） */
  private void pushCallRecordNotify(MessageRecord record) {
    CommonProto.MessageContent message = CommonProto.MessageContent.newBuilder()
      .setMsgTypeValue(record.getMsgType())
      .setContent(ByteString.copyFromUtf8(record.getContent() != null ? record.getContent() : ""))      .setTimestamp(record.getCreatedAt())
      .build();
    ChatProto.C2CNotify notify = ChatProto.C2CNotify.newBuilder()
      .setSenderId(record.getSenderId())
      .setRecipientId(record.getRecipientId())
      .setMessage(message)
      .setSeq(record.getSeq())
      .setMessageId(record.getId())
      .build();
    pushRouter.push(new PushEnvelope(String.valueOf(record.getRecipientId()),
      CMD_C2C_NOTIFY_VALUE, notify.toByteArray()));
  }

  private Future<Void> notifyPeer(CallSession session, int reason, long endedBy) {
    if (endedBy == 0) {
      pushEnded(session.callId, session.callerId, reason, 0);
      return pushEnded(session.callId, session.calleeId, reason, 0);
    }
    return pushEnded(session.callId, session.peerOf(endedBy), reason, endedBy);
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
    store.clearBusy(session.callerId);
    store.clearBusy(session.calleeId);
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
