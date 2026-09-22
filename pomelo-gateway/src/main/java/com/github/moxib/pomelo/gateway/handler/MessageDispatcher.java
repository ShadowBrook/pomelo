package com.github.moxib.pomelo.gateway.handler;

import com.github.moxib.pomelo.codec.ProtobufCodec;
import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.config.JwtTokenParser;
import com.github.moxib.pomelo.config.SessionRouteTable;
import com.github.moxib.pomelo.metrics.PomeloMetrics;
import com.github.moxib.pomelo.model.PushCodec;
import com.github.moxib.pomelo.model.PushEnvelope;
import com.github.moxib.pomelo.proto.auth.AuthProto;
import com.github.moxib.pomelo.proto.common.CommonProto;
import com.github.moxib.pomelo.proto.ctrl.CtrlProto;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.*;

/**
 * 消息分发器 — Gateway 侧（EventBus 转发 + 精确路由推送 + 同端型互踢）。
 */
public class MessageDispatcher {

  private static final Logger LOG = LoggerFactory.getLogger(MessageDispatcher.class);

  /** 顶号通知文案（客户端据此登出并回到登录页） */
  static final String KICK_REASON = "账号在其他同类型设备上登录，本会话已下线";

  private final Vertx vertx;
  private final SessionRegistry sessionRegistry;
  private final SessionRouteTable routeTable;
  private final JwtTokenParser jwtParser;
  private final long heartbeatTimeoutMs;
  // TCP/WS Verticle 共享同一 Dispatcher 时 stop() 会被调用两次，需幂等
  private final AtomicBoolean stopped = new AtomicBoolean(false);

  public MessageDispatcher(Vertx vertx, SessionRegistry sessionRegistry, long heartbeatTimeoutMs) {
    this.vertx = vertx;
    this.sessionRegistry = sessionRegistry;
    this.routeTable = new SessionRouteTable(vertx);
    this.heartbeatTimeoutMs = heartbeatTimeoutMs;
    // AUTH_REQ token 验签解析（与 logic TokenService 复用同一 JwtTokenParser）
    this.jwtParser = new JwtTokenParser(vertx);
    String nodeId = routeTable.getNodeId();
    if (routeTable.isRoutingAvailable()) {
      // 集群：logic 按路由精确投递到本节点专属地址（广播兜底已移除，不再订阅 gateway.push）
      vertx.eventBus().consumer("gateway.push." + nodeId, msg -> deliverPush(msg.body()));
      // 集群：顶号时旧会话可能在其他节点，经此地址通知对方节点执行本地踢
      vertx.eventBus().<JsonObject>consumer("gateway.kick." + nodeId, msg -> {
        JsonObject kick = msg.body();
        SessionRegistry.Session old =
          sessionRegistry.getSession(kick.getString("userId"), kick.getString("platform"));
        if (old != null) {
          LOG.info("跨节点踢下线: userId={} platform={}", old.getUserId(), old.getPlatform());
          kickLocal(old, kick.getString("reason", KICK_REASON));
        }
      });
      LOG.info("Push/kick consumer registered: gateway.push.{}, gateway.kick.{}", nodeId, nodeId);
    } else {
      // 单进程：logic 经本地 EventBus 点对点投递到本地址
      vertx.eventBus().consumer("gateway.push", msg -> deliverPush(msg.body()));
      LOG.info("Push consumer registered: gateway.push (standalone)");
    }
  }

  /**
   * 节点下线：清理本节点注册的 session 路由（按端型槽位）。
   * 用户连接随节点关闭断开，重连到其他节点后由新节点重新注册；
   * 节点存活标记由 cluster manager 的 nodeInfo 目录自行过期，无需在此清理。
   */
  public Future<Void> stop() {
    if (!stopped.compareAndSet(false, true)) {
      return Future.succeededFuture();
    }
    for (SessionRegistry.Session session : sessionRegistry.getOnlineSessions()) {
      routeTable.unregister(session.getUserId(), session.getPlatform());
    }
    return Future.succeededFuture();
  }

  /** 构造 PONG 响应（心跳由 gateway 本地应答） */
  private ImMessage buildPong(ImMessage request) {
    return ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId((byte) 0)
      .cmd(CMD_PONG_VALUE)
      .messageId(request.getMessageId())
      .build();
  }

  /** 解析 push 并投递给本地连接的用户（EventBus 上 push 以 PushCodec Buffer 传输） */
  private void deliverPush(Object msgBody) {
    if (!(msgBody instanceof Buffer buf)) {
      LOG.warn("Unknown push body type: {}", msgBody.getClass().getName());
      return;
    }
    PushEnvelope env = PushCodec.decode(buf);
    deliverToConnection(env);
  }

  private void deliverToConnection(PushEnvelope env) {
    // push target 统一使用 NanoID (userId)
    String userId = env.getTargetUserId();
    String platform = env.getTargetPlatform();
    if (platform == null || platform.isEmpty()) {
      // 不区分端型：投递给该用户在本节点的全部端会话
      for (SessionRegistry.Session session : sessionRegistry.getSessionsByUserId(userId)) {
        writePush(session, env);
      }
      return;
    }
    SessionRegistry.Session session = sessionRegistry.getSession(userId, platform);
    if (session != null) {
      writePush(session, env);
    }
  }

  private void writePush(SessionRegistry.Session session, PushEnvelope env) {
    // e2e 投递延迟（服务端落库 → gateway 写入连接）；sentAt=0 为旧格式信封，跳过
    long sentAt = env.getSentAtEpochMs();
    if (sentAt > 0) {
      PomeloMetrics.histogramTimer("im.push.e2e.latency")
        .record(System.currentTimeMillis() - sentAt, TimeUnit.MILLISECONDS);
    }
    ImMessage imMsg = ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId((byte) 0)
      .cmd(env.getCmd())
      .messageId(env.getCorrelationMsgId() != null ? env.getCorrelationMsgId() : "")
      .body(env.getBody())
      .build();
    session.connection.write(imMsg.encodeToWire());
  }

  /**
   * 断连清理：注销会话并条件删除其端型路由。
   * TCP/WS Verticle 的 closeHandler/exceptionHandler 统一走这里。
   */
  public void onConnectionClosed(Connection connection) {
    SessionRegistry.Session session = sessionRegistry.unregisterByConnection(connection);
    if (session != null) {
      routeTable.unregister(session.getUserId(), session.getPlatform());
    }
  }

  public void dispatch(Connection connection, ImMessage message) {
    // 身份只取本节点 SessionRegistry 的认证结果；客户端携带的同名 varHeader 一律不可信
    SessionRegistry.Session session = sessionRegistry.getSessionByConnection(connection);
    int cmd = message.getCmd();

    if (session != null) {
      // 任何消息都证明用户在线，重置心跳定时器（以会话身份为准）
      sessionRegistry.resetHeartbeatTimer(session, heartbeatTimeoutMs);
      normalizeSenderHeaders(message, session);
    } else if (cmd != CMD_PING_VALUE && cmd != CMD_AUTH_REQ_VALUE) {
      // 未认证连接仅放行心跳与认证，防止伪造 varHeader 调用业务命令
      LOG.warn("未认证连接请求业务命令，已拒绝: cmd=0x{} remote={}",
        Integer.toHexString(cmd), connection.remoteAddress());
      sendErrorToClient(connection, message, ErrorCode.UNAUTHORIZED, "请先完成认证");
      return;
    }

    // 心跳由 gateway 本地应答
    if (cmd == CMD_PING_VALUE) {
      connection.write(buildPong(message).encodeToWire());
      return;
    }

    String address = cmdToAddress(cmd);
    if (address == null) {
      handleUnknownCmd(connection, message);
      return;
    }
    Buffer wire = message.encodeToWire();
    vertx.eventBus().<Buffer>request(address, wire)
      .onSuccess(replyMsg -> {
        Buffer respBuf = replyMsg.body();
        ImMessage response = new ImMessage();
        response.readFromWire(respBuf.getBuffer(4, respBuf.length()));
        handleSessionUpdates(connection, message, response);
        connection.write(respBuf);
      })
      .onFailure(cause -> {
        LOG.warn("EventBus request failed: address={} cause={}", address, cause.getMessage());
        sendErrorToClient(connection, message, ErrorCode.INTERNAL_ERROR, cause.getMessage());
      });
  }

  /**
   * 以认证会话为准覆写发送者身份头（userId/userName/nickname），
   * 使 logic 层从 varHeader 取到的身份永远可信。
   * peerId、token 等其余头保留（属于请求参数或客户端自身凭证）。
   * 重建头 Map 而非原地修改，避免依赖解码产物可变。
   */
  private void normalizeSenderHeaders(ImMessage message, SessionRegistry.Session session) {
    Map<String, String> normalized = new HashMap<>();
    Map<String, String> headers = message.getVarHeaders();
    if (headers != null) {
      normalized.putAll(headers);
    }
    normalized.put("userId", session.getUserId());
    if (session.userName != null) {
      normalized.put("userName", session.userName);
    }
    if (session.nickname != null) {
      normalized.put("nickname", session.nickname);
    }
    message.setVarHeaders(normalized);
  }

  /**
   * 处理 auth/logout 响应对应的 session 变更。
   * 登录成功时从 AUTH_REQ 请求的 token 验签解析用户资料（JWT claims 含 userId/id/userName/nickname），
   * 无需 logic 回传任何字段。
   */
  private void handleSessionUpdates(Connection connection, ImMessage request, ImMessage response) {
    if (response.getCmd() == CMD_AUTH_RESP_VALUE) {
      if (isAuthSuccess(response)) {
        registerSessionFromToken(connection, request);
      }
      return;
    }
    if (response.getCmd() == CMD_LOGOUT_RESP_VALUE) {
      // 以连接的认证身份注销，不信任请求中携带的 userId
      SessionRegistry.Session session = sessionRegistry.unregisterByConnection(connection);
      if (session != null) {
        routeTable.unregister(session.getUserId(), session.getPlatform());
        LOG.info("Session 已注销: userId={} platform={}", session.getUserId(), session.getPlatform());
      }
    }
  }

  /** AUTH_REQ 的 token 验签 + 注册 session + 同端型顶号踢下线 */
  private void registerSessionFromToken(Connection connection, ImMessage request) {
    AuthProto.AuthReq auth = decodeAuthReq(request);
    if (auth == null || auth.getToken() == null || auth.getToken().isEmpty()) {
      return;
    }
    String platform = SessionRouteTable.normalizePlatform(auth.getPlatform());
    jwtParser.validate(auth.getToken())
      .onSuccess(claims -> {
        if (claims == null) {
          LOG.warn("Token 无效，无法注册 session");
          return;
        }
        long id = claims.getLong("id", 0L);
        // 优先使用 numeric id claim（Snowflake），兼容旧 token 中 sub 为 NanoID 的情况
        String userId = id != 0 ? String.valueOf(id) : claims.getString("sub");
        if (userId == null || userId.isEmpty()) {
          return;
        }
        String userName = claims.getString("userName", "");
        String nickname = claims.getString("nickname", "");
        // 同端型槽位注册；被顶掉的旧会话先通知后断连（客户端据此登出而非重连）
        SessionRegistry.Session old =
          sessionRegistry.register(userId, platform, id, connection, userName, nickname, null);
        if (old != null && old.connection != connection) {
          LOG.info("顶号踢下线: userId={} platform={} remote={}",
            userId, platform, old.connection.remoteAddress());
          kickLocal(old, KICK_REASON);
        }
        // 跨节点顶号：必须在该端型路由被本节点覆盖前解析出旧节点
        routeTable.resolve(userId, platform)
          .compose(oldNode -> {
            if (oldNode != null && !oldNode.equals(routeTable.getNodeId())) {
              kickRemote(oldNode, userId, platform, KICK_REASON);
            }
            return routeTable.register(userId, platform);
          });
        sessionRegistry.startHeartbeatTimer(
          sessionRegistry.getSession(userId, platform), heartbeatTimeoutMs);
        LOG.info("Session 已注册: userId={} id={} platform={}", userId, id, platform);
      })
      .onFailure(e -> LOG.warn("Token 解析失败，无法注册 session: {}", e.getMessage()));
  }

  /**
   * 本地踢下线：先投递 KICK_OFFLINE 通知，写完成后再断连，
   * 保证客户端先收到原因（据此登出回登录页）而非当作普通断线重连。
   */
  void kickLocal(SessionRegistry.Session old, String reason) {
    byte[] body = CtrlProto.CtrlNotify.newBuilder()
      .setCtrlType(CtrlProto.CtrlType.CTRL_TYPE_KICK_OFFLINE)
      .setReason(reason)
      .build().toByteArray();
    ImMessage notify = ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId((byte) 0)
      .cmd(CMD_CTRL_NOTIFY_VALUE)
      .body(body)
      .build();
    old.connection.write(notify.encodeToWire())
      .onComplete(v -> old.connection.close());
  }

  /** 跨节点踢下线：通知旧路由所在节点执行本地踢（覆盖路由前发出） */
  private void kickRemote(String nodeId, String userId, String platform, String reason) {
    JsonObject kick = new JsonObject()
      .put("userId", userId)
      .put("platform", platform)
      .put("reason", reason);
    vertx.eventBus().send("gateway.kick." + nodeId, kick);
  }

  /** 判断 AUTH_RESP 是否成功（code == 0），body 一律按 Protobuf 解析 */
  private boolean isAuthSuccess(ImMessage response) {
    byte[] body = response.getBody();
    if (body == null || body.length == 0) {
      return false;
    }
    try {
      return ((AuthProto.AuthResp) ProtobufCodec.getCodec(CMD_AUTH_RESP_VALUE).decode(body)).getCode() == 0;
    } catch (Exception e) {
      LOG.warn("AUTH_RESP 解析失败: {}", e.getMessage());
      return false;
    }
  }

  /** 解析 AUTH_REQ 请求体（token + platform），失败返回 null */
  private AuthProto.AuthReq decodeAuthReq(ImMessage request) {
    byte[] body = request.getBody();
    if (body == null || body.length == 0) {
      return null;
    }
    try {
      return (AuthProto.AuthReq) ProtobufCodec.getCodec(CMD_AUTH_REQ_VALUE).decode(body);
    } catch (Exception e) {
      LOG.warn("AUTH_REQ 解析失败: {}", e.getMessage());
      return null;
    }
  }

  private static String cmdToAddress(int cmd) {
    if (cmd == CMD_C2C_REQ_VALUE)          return "logic.c2c";
    if (cmd == CMD_C2G_REQ_VALUE)          return "logic.c2g";
    if (cmd == CMD_GROUP_PULL_MSG_REQ_VALUE)  return "logic.gpull";
    if (cmd == CMD_GROUP_ACK_REQ_VALUE)       return "logic.gack";
    if (cmd >= 0x0070 && cmd <= 0x009D)       return "logic.group";
    if (cmd == CMD_AUTH_REQ_VALUE)         return "logic.auth";
    if (cmd == CMD_LOGOUT_REQ_VALUE)       return "logic.auth";
    if (cmd == CMD_CTRL_REQ_VALUE)         return "logic.ctrl";
    if (cmd == CMD_ACK_REQ_VALUE)          return "logic.ack";
    if (cmd == CMD_UPLOAD_REQ_VALUE)       return "logic.upload";
    if (cmd == CMD_PROFILE_UPDATE_REQ_VALUE) return "logic.profile";
    if (cmd == CMD_PULL_REQ_VALUE)         return "logic.pull";
    // 音视频通话（0xB0~0xB8 段；群命令 0x0070~0x009D 的硬编码区间勿复用）
    if (cmd >= CMD_CALL_INVITE_REQ_VALUE && cmd <= CMD_CALL_TOKEN_REQ_VALUE) return "logic.call";
    if (cmd == CMD_FRIEND_SEARCH_REQ_VALUE) return "logic.friend";
    if (cmd == CMD_FRIEND_ADD_REQ_VALUE)    return "logic.friend";
    if (cmd == CMD_FRIEND_ACCEPT_REQ_VALUE) return "logic.friend";
    if (cmd == CMD_FRIEND_DELETE_REQ_VALUE) return "logic.friend";
    return null;
  }

  private void handleUnknownCmd(Connection connection, ImMessage request) {
    String msg = "Unknown cmd: 0x" + Integer.toHexString(request.getCmd());
    byte[] body = CommonProto.ErrorBody.newBuilder()
      .setCode(ErrorCode.UNKNOWN_CMD.getCode()).setMessage(msg).build().toByteArray();
    ImMessage response = ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER).version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId((byte) 0).cmd(CMD_ERROR_VALUE).messageId(request.getMessageId()).body(body)
      .build();
    connection.write(response.encodeToWire());
  }

  private void sendErrorToClient(Connection connection, ImMessage request, ErrorCode errorCode, String detail) {
    String msg = detail != null ? detail : errorCode.getDefaultMessage();
    byte[] body = CommonProto.ErrorBody.newBuilder()
      .setCode(errorCode.getCode()).setMessage(msg).build().toByteArray();
    ImMessage response = ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER).version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId((byte) 0).cmd(CMD_ERROR_VALUE).messageId(request.getMessageId()).body(body)
      .build();
    connection.write(response.encodeToWire());
  }

  public SessionRegistry getSessionRegistry() {
    return sessionRegistry;
  }

  public SessionRouteTable getRouteTable() {
    return routeTable;
  }
}
