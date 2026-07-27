package com.github.moxib.pomelo.gateway.handler;

import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.model.PushEnvelope;
import com.github.moxib.pomelo.proto.common.CommonProto;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.*;

/**
 * 消息分发器 — Gateway 侧（EventBus 转发模式）。
 * 将客户端请求通过 EventBus request() 转发到 logic-server，
 * 并订阅 gateway.push 地址，将 logic-server 的推送投递给本地连接。
 */
public class MessageDispatcher {

  private static final Logger LOG = LoggerFactory.getLogger(MessageDispatcher.class);

  private final Vertx vertx;
  private final SessionRegistry sessionRegistry;

  public MessageDispatcher(Vertx vertx, SessionRegistry sessionRegistry) {
    this.vertx = vertx;
    this.sessionRegistry = sessionRegistry;
    vertx.eventBus().consumer("gateway.push", this::onPushMessage);
  }

  /**
   * 收到 logic-server 的推送消息，投递给本地连接的用户。
   */
  private void onPushMessage(io.vertx.core.eventbus.Message<JsonObject> msg) {
    PushEnvelope env = msg.body().mapTo(PushEnvelope.class);
    // 先按数字 id 查，再按 NanoID userId 查
    Connection conn = null;
    long targetId = 0;
    try {
      targetId = Long.parseLong(env.getTargetUserId());
      conn = sessionRegistry.getConnection(targetId);
    } catch (NumberFormatException e) {
      conn = sessionRegistry.getConnectionByUserId(env.getTargetUserId());
    }
    if (conn == null) {
      LOG.debug("push target {} 不在本节点，忽略", env.getTargetUserId());
      return;
    }
    // 根据接收方实际 codec 选择 body：JSON 用户用 jsonBody，PB 用户用 body
    byte recipientCodec = sessionRegistry.getCodec(targetId);
    byte[] pushBody;
    byte pushCodecId;
    if (recipientCodec == 1 && env.getJsonBody() != null && env.getJsonBody().length > 0) {
      pushBody = env.getJsonBody();
      pushCodecId = 1;
    } else {
      pushBody = env.getBody();
      pushCodecId = env.getCodecId();
    }
    ImMessage imMsg = ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId(pushCodecId)
      .cmd(env.getCmd())
      .messageId(env.getCorrelationMsgId() != null ? env.getCorrelationMsgId() : "")
      .body(pushBody)
      .build();
    conn.write(imMsg.encodeToWire());
  }

  /**
   * 将客户端请求转发到 logic-server。
   * ImMessage 编码为 wire bytes（Buffer）在 EventBus 上传输。
   */
  public void dispatch(Connection connection, ImMessage message) {
    String address = cmdToAddress(message.getCmd());
    if (address == null) {
      handleUnknownCmd(connection, message);
      return;
    }
    // 从 SessionRegistry 补充发送者的 userName/nickname 到 varHeaders
    enrichWithSenderInfo(message);
    Buffer wire = message.encodeToWire();
    vertx.eventBus().<Buffer>request(address, wire)
      .onSuccess(replyMsg -> {
        Buffer respBuf = replyMsg.body();
        // 解析响应（跳过 4 字节长度前缀），检查是否需要注册/注销 session
        ImMessage response = new ImMessage();
        response.readFromWire(respBuf.getBuffer(4, respBuf.length()));
        handleSessionUpdates(connection, response);
        connection.write(respBuf);
      })
      .onFailure(cause -> {
        LOG.warn("EventBus request failed: address={} cause={}", address, cause.getMessage());
        sendErrorToClient(connection, message, cause.getMessage());
      });
  }

  /**
   * 处理 Login/Logout 响应中的 Session 更新。
   */
  private void handleSessionUpdates(Connection connection, ImMessage response) {
    Map<String, String> headers = response.getVarHeaders();
    if (headers == null) return;

    String loginUserId = headers.get("loginUserId");
    if (loginUserId != null && !loginUserId.isEmpty()) {
      long id = Long.parseLong(headers.getOrDefault("loginId", "0"));
      String userName = headers.getOrDefault("loginUserName", "");
      String nickname = headers.getOrDefault("loginNickname", "");
      byte codecId = Byte.parseByte(headers.getOrDefault("loginCodecId", "0"));
      String token = headers.getOrDefault("loginToken", "");
      sessionRegistry.register(loginUserId, id, connection, codecId, userName, nickname, token);
      LOG.info("Session 已注册: userId={} id={}", loginUserId, id);
    }

    String logoutUserId = headers.get("logoutUserId");
    if (logoutUserId != null && !logoutUserId.isEmpty()) {
      sessionRegistry.unregisterByUserId(logoutUserId);
      LOG.info("Session 已注销: userId={}", logoutUserId);
    }
  }

  /**
   * 从 SessionRegistry 获取发送者信息，补充到 varHeaders 中，
   * 供 logic-server 构建推送时使用（senderUserName, senderNickname）。
   */
  private void enrichWithSenderInfo(ImMessage message) {
    Map<String, String> headers = message.getVarHeaders();
    if (headers == null) return;
    String userId = headers.get("userId");
    if (userId == null) return;
    long id = sessionRegistry.getId(userId);
    if (id == 0) return;
    String userName = sessionRegistry.getUserName(id);
    String nickname = sessionRegistry.getNickname(id);
    if (userName != null && !headers.containsKey("userName")) {
      headers.put("userName", userName);
    }
    if (nickname != null && !headers.containsKey("nickname")) {
      headers.put("nickname", nickname);
    }
  }

  /**
   * cmd → EventBus 地址映射。
   */
  private static String cmdToAddress(int cmd) {
    if (cmd == CMD_C2C_REQ_VALUE)          return "logic.c2c";
    if (cmd == CMD_C2G_REQ_VALUE)          return "logic.c2g";
    if (cmd == CMD_AUTH_REQ_VALUE)         return "logic.auth";
    if (cmd == CMD_LOGOUT_REQ_VALUE)       return "logic.auth";
    if (cmd == CMD_CTRL_REQ_VALUE)         return "logic.ctrl";
    if (cmd == CMD_ACK_REQ_VALUE)          return "logic.ack";
    if (cmd == CMD_PULL_REQ_VALUE)         return "logic.pull";
    if (cmd == CMD_PING_VALUE)             return "logic.ping";
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
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId(codecId)
      .cmd(CMD_ERROR_VALUE)
      .messageId(request.getMessageId())
      .body(body)
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
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId(codecId)
      .cmd(CMD_ERROR_VALUE)
      .messageId(request.getMessageId())
      .body(body)
      .build();
    connection.write(response.encodeToWire());
  }

  public SessionRegistry getSessionRegistry() {
    return sessionRegistry;
  }
}
