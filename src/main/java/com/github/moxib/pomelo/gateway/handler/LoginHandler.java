package com.github.moxib.pomelo.gateway.handler;

import com.github.moxib.pomelo.codec.CodecRegistry;
import com.github.moxib.pomelo.codec.ProtobufCodec;
import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.proto.auth.AuthProto;
import com.github.moxib.pomelo.service.MessageRepository;
import com.github.moxib.pomelo.service.MessageService;
import com.github.moxib.pomelo.service.RedisOnlineStatus;
import com.github.moxib.pomelo.service.TokenService;
import com.github.moxib.pomelo.service.model.requests.LoginRequest;
import com.github.moxib.pomelo.utils.IdGenerator;
import io.vertx.core.json.JsonObject;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.CMD_AUTH_RESP_VALUE;

/**
 * 登录消息处理器。
 * Token 路径：JWT 验证通过后直接从 claims 取用户信息，零 DB 查询。
 * 无 token 路径（fallback）：查 DB 加载用户信息。
 */
public class LoginHandler extends AbstractMessageHandler {

  private static final Logger LOG = LoggerFactory.getLogger(LoginHandler.class);

  public LoginHandler(Vertx vertx, CodecRegistry codecRegistry,
                       SessionRegistry sessionRegistry, MessageRepository messageRepo,
                       MessageService messageService, IdGenerator idGenerator) {
    super(vertx, codecRegistry, sessionRegistry, messageRepo, messageService, idGenerator);
  }

  @Override
  public void handle(Connection connection, ImMessage message) {
    try {
      LoginRequest req = decodeRequest(message, LoginRequest.class);
      String token = req.token();

      // Token 路径：JWT 已验明身份，claims 中携带完整用户信息
      if (token != null && !token.isEmpty()) {
        TokenService ts = TokenService.get(vertx);
        ts.isBlacklisted(token).onComplete(ar -> {
          if (ar.succeeded() && ar.result()) {
            LOG.warn("Token 已被撤销，拒绝连接");
            sendErrorAndClose(connection, message, ErrorCode.UNAUTHORIZED, "Token 已失效");
            return;
          }
          ts.validate(token).onComplete(var -> {
            if (!var.succeeded() || var.result() == null) {
              LOG.warn("Token 无效，关闭连接");
              sendErrorAndClose(connection, message, ErrorCode.UNAUTHORIZED, "Token 无效");
              return;
            }
            // 从 JWT claims 直接取身份信息，无需查 DB
            JsonObject claims = var.result();
            String userId = claims.getString("sub");
            long id = claims.getLong("id", 0L);
            String userName = claims.getString("userName");
            String nickname = claims.getString("nickname");
            completeLogin(connection, message, userId, id, userName, nickname, "", token, message.getCodecId());
          });
        });
      } else {
        sendErrorAndClose(connection, message, ErrorCode.INTERNAL_ERROR, "登录失败：");
      }
    } catch (Exception e) {
      LOG.error("登录处理失败", e);
      sendErrorAndClose(connection, message, ErrorCode.INTERNAL_ERROR, "登录失败：" + e.getMessage());
    }
  }

  private void completeLogin(Connection connection, ImMessage message,
                              String userId, long id, String userName, String nickname, String avatar,
                              String token, byte codecId) {
    LOG.info("登录成功: userId={} userName={} codec={}", userId, userName, codecId == 0 ? "PB" : "JSON");

    sessionRegistry.register(userId, id, connection, codecId, userName, nickname, token);
    RedisOnlineStatus.get(vertx).setOnline(userId);

    Object respBody;
    if (codecId == ProtobufCodec.CODEC_ID) {
      respBody = AuthProto.AuthResp.newBuilder()
        .setCode(0).setMessage("success").setUserId(userId).build();
    } else {
      JsonObject json = jsonBody();
      json.put("code", 0).put("message", "success");
      json.put("userId", userId);
      json.put("userName", userName);
      json.put("nickname", nickname != null ? nickname : "");
      json.put("avatar", avatar != null ? avatar : "");
      respBody = json;
    }

    ImMessage response = buildResponse(message, CMD_AUTH_RESP_VALUE, respBody);
    sendResponse(connection, response);
  }

  private void sendErrorAndClose(Connection connection, ImMessage request, ErrorCode error, String detail) {
    sendErrorResponse(connection, request, CMD_AUTH_RESP_VALUE, error, detail);
    connection.close();
  }
}
