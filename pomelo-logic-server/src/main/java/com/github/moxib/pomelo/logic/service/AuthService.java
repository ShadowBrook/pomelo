package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.codec.CodecRegistry;
import com.github.moxib.pomelo.codec.ProtobufCodec;
import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.logic.infrastructure.RedisOnlineStatus;
import com.github.moxib.pomelo.logic.infrastructure.TokenService;
import com.github.moxib.pomelo.logic.model.requests.LoginRequest;
import com.github.moxib.pomelo.proto.auth.AuthProto;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.*;

public class AuthService extends ServiceBase {

  private static final Logger LOG = LoggerFactory.getLogger(AuthService.class);

  private final Vertx vertx;
  private final CodecRegistry codecRegistry;

  public AuthService(Vertx vertx) {
    this.vertx = vertx;
    this.codecRegistry = new CodecRegistry();
    codecRegistry.registerProtobuf(CMD_AUTH_REQ_VALUE, AuthProto.AuthReq.parser(), LoginRequest::fromProto, LoginRequest.class);
    codecRegistry.registerJson(CMD_AUTH_REQ_VALUE, LoginRequest.class);
  }

  public Future<ImMessage> process(ImMessage message) {
    try {
      int cmd = message.getCmd();
      if (cmd == CMD_AUTH_REQ_VALUE) return handleLogin(message);
      if (cmd == CMD_LOGOUT_REQ_VALUE) return handleLogout(message);
      return Future.succeededFuture(buildErrorResp(message, CMD_AUTH_RESP_VALUE, ErrorCode.BAD_REQUEST, "Unknown auth cmd"));
    } catch (Exception e) {
      LOG.error("Auth 处理失败", e);
      return Future.succeededFuture(buildErrorResp(message, CMD_AUTH_RESP_VALUE, ErrorCode.INTERNAL_ERROR, e.getMessage()));
    }
  }

  private Future<ImMessage> handleLogin(ImMessage message) {
    LoginRequest req = decode(codecRegistry, message, LoginRequest.class);
    String token = req.token();

    if (token == null || token.isEmpty()) {
      return Future.succeededFuture(buildErrorResp(message, CMD_AUTH_RESP_VALUE, ErrorCode.BAD_REQUEST, "token 不能为空"));
    }

    TokenService ts = TokenService.get(vertx);
    return ts.isBlacklisted(token).compose(blacklisted -> {
      if (blacklisted) {
        LOG.warn("Token 已被撤销");
        return Future.succeededFuture(buildErrorResp(message, CMD_AUTH_RESP_VALUE, ErrorCode.UNAUTHORIZED, "Token 已失效"));
      }
      return ts.validate(token).compose(claims -> {
        if (claims == null) {
          return Future.succeededFuture(buildErrorResp(message, CMD_AUTH_RESP_VALUE, ErrorCode.UNAUTHORIZED, "Token 无效"));
        }
        String userId = claims.getString("sub");
        long id = claims.getLong("id", 0L);
        String userName = claims.getString("userName");
        String nickname = claims.getString("nickname");
        byte codecId = message.getCodecId();

        LOG.info("登录成功: userId={} userName={} codec={}", userId, userName, codecId == 0 ? "PB" : "JSON");
        RedisOnlineStatus.get(vertx).setOnline(userId);

        Object respBody;
        if (codecId == ProtobufCodec.CODEC_ID) {
          respBody = AuthProto.AuthResp.newBuilder()
            .setCode(0).setMessage("success").setUserId(userId).build();
        } else {
          respBody = new JsonObject()
            .put("code", 0).put("message", "success")
            .put("userId", userId).put("userName", userName)
            .put("nickname", nickname != null ? nickname : "");
        }

        ImMessage response = buildResponse(message, CMD_AUTH_RESP_VALUE, respBody);
        // 将用户身份信息放入 varHeaders，Gateway 在 dispatch 回调中提取并注册到 SessionRegistry
        response.getVarHeaders().put("loginUserId", userId);
        response.getVarHeaders().put("loginId", String.valueOf(id));
        response.getVarHeaders().put("loginUserName", userName != null ? userName : "");
        response.getVarHeaders().put("loginNickname", nickname != null ? nickname : "");
        response.getVarHeaders().put("loginCodecId", String.valueOf(codecId));
        response.getVarHeaders().put("loginToken", token);
        return Future.succeededFuture(response);
      });
    });
  }

  private Future<ImMessage> handleLogout(ImMessage message) {
    String userId = getUserIdFromHeaders(message);
    if (userId == null) userId = getBodyAsString(message);
    LOG.info("登出请求: userId={}", userId);

    byte codecId = message.getCodecId();
    if (userId != null && !userId.isEmpty()) {
      RedisOnlineStatus.get(vertx).setOffline(userId);
    }

    // Token 黑名单：token 从 varHeaders 传入
    String token = null;
    java.util.Map<String, String> headers = message.getVarHeaders();
    if (headers != null) token = headers.get("token");
    if (token != null && !token.isEmpty() && !"test-token".equals(token)) {
      TokenService.get(vertx).blacklist(token);
    }

    Object respBody = codecId == ProtobufCodec.CODEC_ID
      ? AuthProto.LogoutResp.newBuilder().setCode(0).setMessage("登出成功").build()
      : new JsonObject().put("code", 0).put("message", "登出成功");

    ImMessage response = buildResponse(message, CMD_LOGOUT_RESP_VALUE, respBody);
    response.getVarHeaders().put("status", "success");
    response.getVarHeaders().put("logoutUserId", userId != null ? userId : "");
    return Future.succeededFuture(response);
  }
}
