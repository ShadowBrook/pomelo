package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.logic.infrastructure.TokenService;
import com.github.moxib.pomelo.logic.model.requests.LoginRequest;
import com.github.moxib.pomelo.proto.auth.AuthProto;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.*;

public class AuthService extends ServiceBase {

  private static final Logger LOG = LoggerFactory.getLogger(AuthService.class);

  private final Vertx vertx;

  public AuthService(Vertx vertx) {
    this.vertx = vertx;
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
    LoginRequest req = decode(message, LoginRequest.class);
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
        long id = claims.getLong("id", 0L);
        // 优先使用 numeric id claim（Snowflake），兼容旧 token 中 sub 为 NanoID 的情况
        String userId = id != 0 ? String.valueOf(id) : claims.getString("sub");
        String userName = claims.getString("userName");

        LOG.info("登录成功: userId={} userName={}", userId, userName);

        AuthProto.AuthResp respBody = AuthProto.AuthResp.newBuilder()
          .setCode(0).setMessage("success").setUserId(id).build();

        return Future.succeededFuture(buildResponse(message, CMD_AUTH_RESP_VALUE, respBody));
      });
    });
  }

  private Future<ImMessage> handleLogout(ImMessage message) {
    String userId = getUserIdFromHeaders(message);
    LOG.info("登出请求: userId={}", userId);

    String token = null;
    Map<String, String> headers = message.getVarHeaders();
    if (headers != null) token = headers.get("token");
    if (token != null && !token.isEmpty() && !"test-token".equals(token)) {
      TokenService.get(vertx).blacklist(token);
    }

    AuthProto.LogoutResp respBody = AuthProto.LogoutResp.newBuilder()
      .setCode(0).setMessage("登出成功").build();

    return Future.succeededFuture(buildResponse(message, CMD_LOGOUT_RESP_VALUE, respBody));
  }
}
