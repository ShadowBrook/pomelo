package com.github.moxib.pomelo.gateway.handler;

import com.github.moxib.pomelo.codec.ProtobufCodec;
import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.config.ConfigHolder;
import com.github.moxib.pomelo.config.JwtTokenParser;
import com.github.moxib.pomelo.config.SessionRouteTable;
import com.github.moxib.pomelo.model.PushCodec;
import com.github.moxib.pomelo.model.PushEnvelope;
import com.github.moxib.pomelo.proto.auth.AuthProto;
import com.github.moxib.pomelo.proto.common.CommonProto;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.*;

/**
 * 消息分发器 — Gateway 侧（EventBus 转发 + 精确路由推送）。
 */
public class MessageDispatcher {

  private static final Logger LOG = LoggerFactory.getLogger(MessageDispatcher.class);

  private final Vertx vertx;
  private final SessionRegistry sessionRegistry;
  private final SessionRouteTable routeTable;
  private final JwtTokenParser jwtParser;
  private final long heartbeatTimeoutMs;
  private final long nodeTtlMs;
  private long heartbeatTimerId = -1;

  public MessageDispatcher(Vertx vertx, SessionRegistry sessionRegistry, long heartbeatTimeoutMs) {
    this.vertx = vertx;
    this.sessionRegistry = sessionRegistry;
    this.routeTable = new SessionRouteTable(vertx);
    this.heartbeatTimeoutMs = heartbeatTimeoutMs;
    this.nodeTtlMs = ConfigHolder.getLong("gateway.cluster.nodeTtlMs", 60000L);
    // AUTH_REQ token 验签解析（与 logic TokenService 复用同一 JwtTokenParser）
    this.jwtParser = new JwtTokenParser(vertx);
    String nodeId = routeTable.getNodeId();
    // 精确路由订阅（logic-server 通过 send 直接投递）
    vertx.eventBus().consumer("gateway.push." + nodeId, msg -> deliverPush(msg.body()));
    // 兜底广播订阅（logic-server 查不到路由时 fallback）
    vertx.eventBus().consumer("gateway.push", msg -> deliverPush(msg.body()));
    LOG.info("Push consumers registered: gateway.push.{} + gateway.push (fallback)", nodeId);
    // 注册节点存活心跳 + 定期续期在线用户 session 路由
    startNodeHeartbeat();
  }

  /**
   * 注册本节点存活标记，并启动节点心跳定时器。
   * 每 nodeTtl/2 续期一次节点存活标记；节点崩溃后标记在 TTL 内自动过期。
   * 用户 session 路由不在此续期——用户在线由客户端心跳驱动，见 {@link #touchHeartbeat}。
   */
  private void startNodeHeartbeat() {
    if (!vertx.isClustered()) {
      return;
    }
    routeTable.registerNode(nodeTtlMs)
      .onSuccess(v -> {
        heartbeatTimerId = vertx.setPeriodic(nodeTtlMs / 2, id -> routeTable.renewNode(nodeTtlMs));
        LOG.info("节点心跳已启动: nodeId={} ttl={}ms interval={}ms", routeTable.getNodeId(), nodeTtlMs, nodeTtlMs / 2);
      })
      .onFailure(e -> LOG.warn("节点心跳注册失败，精确路由将不可靠: {}", e.getMessage()));
  }

  /**
   * 节点下线：取消心跳，清理本节点注册的 session 路由和存活标记。
   * 用户连接随节点关闭断开，重连到其他节点后由新节点重新注册。
   */
  public Future<Void> stop() {
    if (heartbeatTimerId >= 0) {
      vertx.cancelTimer(heartbeatTimerId);
      heartbeatTimerId = -1;
    }
    for (String userId : sessionRegistry.getOnlineUserIds()) {
      routeTable.unregister(userId);
    }
    return routeTable.unregisterNode();
  }

  /** 构造 PONG 响应（心跳由 gateway 本地应答） */
  private ImMessage buildPong(ImMessage request) {
    return ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId(request.getCodecId())
      .cmd(CMD_PONG_VALUE)
      .messageId(request.getMessageId())
      .build();
  }

  /** 解析 push 并投递给本地连接的用户 */
  private void deliverPush(Object msgBody) {
    PushEnvelope env;
    if (msgBody instanceof Buffer buf) {
      env = PushCodec.decode(buf);
    } else if (msgBody instanceof JsonObject json) {
      env = json.mapTo(PushEnvelope.class);
    } else {
      LOG.warn("Unknown push body type: {}", msgBody.getClass().getName());
      return;
    }
    deliverToConnection(env);
  }

  private void deliverToConnection(PushEnvelope env) {
    // push target 统一使用 NanoID (userId)
    String userId = env.getTargetUserId();
    Connection conn = sessionRegistry.getConnectionByUserId(userId);
    if (conn == null) {
      return;
    }
    ImMessage imMsg = ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId(env.getCodecId())
      .cmd(env.getCmd())
      .messageId(env.getCorrelationMsgId() != null ? env.getCorrelationMsgId() : "")
      .body(env.getBody())
      .build();
    conn.write(imMsg.encodeToWire());
  }

  public void dispatch(Connection connection, ImMessage message) {
    // 任何客户端消息都证明用户在线，重置心跳定时器
    touchHeartbeat(message);

    // 心跳由 gateway 本地应答
    if (message.getCmd() == CMD_PING_VALUE) {
      connection.write(buildPong(message).encodeToWire());
      return;
    }

    String address = cmdToAddress(message.getCmd());
    if (address == null) {
      handleUnknownCmd(connection, message);
      return;
    }
    enrichWithSenderInfo(message);
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
        sendErrorToClient(connection, message, cause.getMessage());
      });
  }

  /**
   * 任何客户端消息（含 PING）都证明用户在线，重置连接心跳超时定时器。
   * 仅本地 Vert.x timer，无 Redis 写——session 路由无需续期（节点存活由节点心跳管理）。
   */
  private void touchHeartbeat(ImMessage message) {
    Map<String, String> headers = message.getVarHeaders();
    if (headers == null) return;
    String userId = headers.get("userId");
    if (userId != null && !userId.isEmpty()) {
      sessionRegistry.resetHeartbeatTimer(vertx, userId, heartbeatTimeoutMs);
    }
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
      String userId = getUserIdFromRequest(request);
      if (userId != null && !userId.isEmpty()) {
        sessionRegistry.unregisterByUserId(vertx, userId);
        routeTable.unregister(userId);
        LOG.info("Session 已注销: userId={}", userId);
      }
    }
  }

  /** AUTH_REQ 的 token 验签 + 注册 session */
  private void registerSessionFromToken(Connection connection, ImMessage request) {
    String token = extractToken(request);
    if (token == null || token.isEmpty()) {
      return;
    }
    jwtParser.validate(token)
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
        String platform = claims.getString("platform", "");
        byte codecId = request.getCodecId();
        sessionRegistry.register(userId, id, connection, codecId, userName, nickname, null);
        routeTable.register(userId);
        routeTable.setCodec(userId, platform, codecId);
        sessionRegistry.startHeartbeatTimer(vertx, userId, heartbeatTimeoutMs);
        LOG.info("Session 已注册: userId={} id={}", userId, id);
      })
      .onFailure(e -> LOG.warn("Token 解析失败，无法注册 session: {}", e.getMessage()));
  }

  /** 判断 AUTH_RESP 是否成功（code == 0） */
  private boolean isAuthSuccess(ImMessage response) {
    byte[] body = response.getBody();
    if (body == null || body.length == 0) {
      return false;
    }
    try {
      if (response.getCodecId() == ProtobufCodec.CODEC_ID) {
        return ((AuthProto.AuthResp) ProtobufCodec.getCodec(CMD_AUTH_RESP_VALUE).decode(body)).getCode() == 0;
      }
      return new JsonObject(new String(body, StandardCharsets.UTF_8)).getInteger("code", -1) == 0;
    } catch (Exception e) {
      LOG.warn("AUTH_RESP 解析失败: {}", e.getMessage());
      return false;
    }
  }

  /** 从 AUTH_REQ 请求提取 token（PB/JSON 双 codec） */
  private String extractToken(ImMessage request) {
    byte[] body = request.getBody();
    if (body == null || body.length == 0) {
      return null;
    }
    try {
      if (request.getCodecId() == ProtobufCodec.CODEC_ID) {
        return ((AuthProto.AuthReq) ProtobufCodec.getCodec(CMD_AUTH_REQ_VALUE).decode(body)).getToken();
      }
      return new JsonObject(new String(body, StandardCharsets.UTF_8)).getString("token");
    } catch (Exception e) {
      LOG.warn("AUTH_REQ token 提取失败: {}", e.getMessage());
      return null;
    }
  }

  /** 从请求 varHeaders 或 body 取 userId（LOGOUT_REQ 用） */
  private String getUserIdFromRequest(ImMessage request) {
    Map<String, String> headers = request.getVarHeaders();
    if (headers != null && headers.get("userId") != null && !headers.get("userId").isEmpty()) {
      return headers.get("userId");
    }
    byte[] body = request.getBody();
    if (body != null && body.length > 0) {
      return new String(body, StandardCharsets.UTF_8).trim();
    }
    return null;
  }

  private void enrichWithSenderInfo(ImMessage message) {
    Map<String, String> headers = message.getVarHeaders();
    if (headers == null) return;
    String userId = headers.get("userId");
    if (userId == null) return;
    if (sessionRegistry.getId(userId) == 0) return;
    String userName = sessionRegistry.getUserName(userId);
    String nickname = sessionRegistry.getNickname(userId);
    if (userName != null && !headers.containsKey("userName")) {
      headers.put("userName", userName);
    }
    if (nickname != null && !headers.containsKey("nickname")) {
      headers.put("nickname", nickname);
    }
  }

  private static String cmdToAddress(int cmd) {
    if (cmd == CMD_C2C_REQ_VALUE)          return "logic.c2c";
    if (cmd == CMD_C2G_REQ_VALUE)          return "logic.c2g";
    if (cmd == CMD_GROUP_PULL_MSG_REQ_VALUE)  return "logic.gpull";
    if (cmd == CMD_GROUP_ACK_REQ_VALUE)       return "logic.gack";
    if (cmd >= 0x0070 && cmd <= 0x0099)       return "logic.group";
    if (cmd == CMD_AUTH_REQ_VALUE)         return "logic.auth";
    if (cmd == CMD_LOGOUT_REQ_VALUE)       return "logic.auth";
    if (cmd == CMD_CTRL_REQ_VALUE)         return "logic.ctrl";
    if (cmd == CMD_ACK_REQ_VALUE)          return "logic.ack";
    if (cmd == CMD_PULL_REQ_VALUE)         return "logic.pull";
    if (cmd == CMD_FRIEND_SEARCH_REQ_VALUE) return "logic.friend";
    if (cmd == CMD_FRIEND_ADD_REQ_VALUE)    return "logic.friend";
    if (cmd == CMD_FRIEND_ACCEPT_REQ_VALUE) return "logic.friend";
    if (cmd == CMD_FRIEND_DELETE_REQ_VALUE) return "logic.friend";
    return null;
  }

  private void handleUnknownCmd(Connection connection, ImMessage request) {
    String msg = "Unknown cmd: 0x" + Integer.toHexString(request.getCmd());
    byte codecId = request.getCodecId();
    byte[] body;
    if (codecId == 0) {
      body = CommonProto.ErrorBody.newBuilder()
        .setCode(ErrorCode.UNKNOWN_CMD.getCode()).setMessage(msg).build().toByteArray();
    } else {
      body = new JsonObject().put("code", ErrorCode.UNKNOWN_CMD.getCode()).put("message", msg)
        .toBuffer().getBytes();
    }
    ImMessage response = ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER).version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId(codecId).cmd(CMD_ERROR_VALUE).messageId(request.getMessageId()).body(body)
      .build();
    connection.write(response.encodeToWire());
  }

  private void sendErrorToClient(Connection connection, ImMessage request, String detail) {
    byte codecId = request.getCodecId();
    byte[] body;
    if (codecId == 0) {
      body = CommonProto.ErrorBody.newBuilder()
        .setCode(ErrorCode.INTERNAL_ERROR.getCode()).setMessage(detail).build().toByteArray();
    } else {
      body = new JsonObject().put("code", ErrorCode.INTERNAL_ERROR.getCode()).put("message", detail)
        .toBuffer().getBytes();
    }
    ImMessage response = ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER).version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId(codecId).cmd(CMD_ERROR_VALUE).messageId(request.getMessageId()).body(body)
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
