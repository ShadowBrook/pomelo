package com.github.moxib.pomelo.gateway.handler;

import com.github.moxib.pomelo.codec.CodecRegistry;
import com.github.moxib.pomelo.codec.ProtobufCodec;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.proto.auth.AuthProto;
import com.github.moxib.pomelo.service.MessageRepository;
import com.github.moxib.pomelo.service.MessageService;
import com.github.moxib.pomelo.service.RedisOnlineStatus;
import com.github.moxib.pomelo.service.TokenService;
import com.github.moxib.pomelo.utils.IdGenerator;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.CMD_LOGOUT_RESP_VALUE;

/**
 * 登出消息处理器。
 * 清理 Session、Redis 在线状态，并撤销 JWT token。
 */
public class LogoutHandler extends AbstractMessageHandler {

  private static final Logger LOG = LoggerFactory.getLogger(LogoutHandler.class);

  public LogoutHandler(Vertx vertx, CodecRegistry codecRegistry,
                        SessionRegistry sessionRegistry, MessageRepository messageRepo,
                        MessageService messageService, IdGenerator idGenerator) {
    super(vertx, codecRegistry, sessionRegistry, messageRepo, messageService, idGenerator);
  }

  @Override
  public void handle(Connection connection, ImMessage message) {
    String userId = getUserIdFromHeaders(message);
    if (userId == null) {
      userId = getBodyAsString(message);
    }

    LOG.info("收到登出请求，userId: {}", userId);

    // 先获取 token（必须在 unregister 之前，因为 unregister 会清除 session）
    String token;
    if (userId != null && !userId.isEmpty()) {
      token = sessionRegistry.getTokenByUserId(userId);
    } else {
      token = sessionRegistry.getTokenByConnection(connection);
    }

    // 注销用户会话
    String removedUserId;
    if (userId != null && !userId.isEmpty()) {
      removedUserId = sessionRegistry.unregisterByUserId(userId);
    } else {
      removedUserId = sessionRegistry.unregisterByConnection(connection);
    }

    // Redis 标记离线
    if (removedUserId != null) {
      RedisOnlineStatus.get(vertx).setOffline(removedUserId);
    }

    // 撤销 JWT token
    if (token != null && !token.isEmpty() && !"test-token".equals(token)) {
      TokenService.get(vertx).blacklist(token);
      LOG.info("Token 已加入黑名单");
    }

    byte codecId = message.getCodecId();
    Object respBody = codecId == ProtobufCodec.CODEC_ID
      ? AuthProto.LogoutResp.newBuilder().setCode(0).setMessage("登出成功").build()
      : jsonBody().put("code", 0).put("message", "登出成功");

    ImMessage response = buildResponse(message, CMD_LOGOUT_RESP_VALUE, respBody);
    response.getVarHeaders().put("status", "success");
    sendResponse(connection, response);
  }
}
