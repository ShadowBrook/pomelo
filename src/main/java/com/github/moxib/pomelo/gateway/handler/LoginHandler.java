package com.github.moxib.pomelo.gateway.handler;

import com.github.moxib.pomelo.codec.CodecRegistry;
import com.github.moxib.pomelo.codec.ProtobufCodec;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.proto.auth.AuthProto;
import com.github.moxib.pomelo.service.MessageRepository;
import com.github.moxib.pomelo.service.MessageService;
import com.github.moxib.pomelo.utils.IdGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 登录消息处理器。
 * 支持 Protobuf 和 JSON 双协议，登录后注册 Session（含 codecId）。
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
      byte codecId = message.getCodecId();
      String userId;

      if (codecId == ProtobufCodec.CODEC_ID) {
        AuthProto.AuthReq req = decodeBody(message);
        // AuthReq 没有 userId 字段，从 varHeaders 取
        Map<String, String> headers = message.getVarHeaders();
        userId = headers != null ? headers.get("userId") : null;
      } else {
        JsonNode json = parseJsonBody(message);
        userId = json.has("userId") ? json.get("userId").asText() : null;
        if (userId == null) {
          Map<String, String> headers = message.getVarHeaders();
          userId = headers != null ? headers.get("userId") : null;
        }
      }

      if (userId == null || userId.isEmpty()) {
        sendErrorResponse(connection, message, 400, "userId 不能为空");
        return;
      }

      LOG.info("登录请求: userId={} codec={}", userId, codecId == 0 ? "Protobuf" : "JSON");

      // 注册用户会话 + codecId
      sessionRegistry.register(userId, connection, codecId);

      // 构建响应
      Object respBody;
      if (codecId == ProtobufCodec.CODEC_ID) {
        respBody = AuthProto.AuthResp.newBuilder()
          .setCode(0)
          .setMessage("success")
          .setUserId(userId)
          .build();
      } else {
        ObjectNode json = jsonBody();
        json.put("code", 0);
        json.put("message", "success");
        json.put("userId", userId);
        respBody = json;
      }

      ImMessage response = buildResponse(message, 0x02, respBody);
      sendResponse(connection, response);

    } catch (Exception e) {
      LOG.error("登录处理失败", e);
      sendErrorResponse(connection, message, 500, "登录失败：" + e.getMessage());
    }
  }
}
