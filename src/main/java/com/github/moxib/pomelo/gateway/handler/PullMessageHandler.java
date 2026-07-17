package com.github.moxib.pomelo.gateway.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.moxib.pomelo.codec.CodecRegistry;
import com.github.moxib.pomelo.codec.ProtobufCodec;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.proto.pull.PullProto;
import io.vertx.core.Vertx;
import com.github.moxib.pomelo.utils.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.CMD_PULL_RESP_VALUE;

/**
 * 离线消息拉取处理器
 * 处理客户端拉取离线/历史消息的请求
 */
public class PullMessageHandler extends AbstractMessageHandler {

  private static final Logger LOG = LoggerFactory.getLogger(PullMessageHandler.class);

  public PullMessageHandler(Vertx vertx, CodecRegistry codecRegistry,
                            SessionRegistry sessionRegistry, MessageRepository messageRepo, IdGenerator idGenerator) {
    super(vertx, codecRegistry, sessionRegistry, messageRepo, idGenerator);
  }

  @Override
  public void handle(Connection connection, ImMessage message) {
    try {
      String userId;
      long sinceSeq;
      int limit;

      if (message.getCodecId() == ProtobufCodec.CODEC_ID) {
        PullProto.PullReq req = this.<PullProto.PullReq>decodeBody(message);
        sinceSeq = req.getLastMsgId();
        limit = req.getLimit();
        // PullReq does not carry userId in protobuf; retrieve it from auth headers
        java.util.Map<String, String> headers = message.getVarHeaders();
        userId = headers != null ? headers.get("userId") : null;
      } else {
        JsonNode json = parseJsonBody(message);
        userId = json.get("userId").asText();
        sinceSeq = json.has("lastMsgId") ? json.get("lastMsgId").asLong() : 0;
        limit = json.has("limit") ? json.get("limit").asInt() : 50;
      }

      if (userId == null || userId.isEmpty()) {
        LOG.error("无法解析 userId，连接: {}", connection.remoteAddress());
        sendErrorResponse(connection, message, 401, "未认证用户");
        return;
      }

      LOG.info("收到拉取消息请求，userId: {}, limit: {}, sinceSeq: {}", userId, limit, sinceSeq);

      messageRepo.pullOfflineMessages(userId, sinceSeq, limit)
        .onSuccess(messages -> {
          PullProto.PullResp resp = PullProto.PullResp.newBuilder()
            .setCode(0)
            .setMessage("success")
            .setHasMore(messages != null && messages.size() >= limit)
            .build();

          ImMessage response = ImMessage.builder()
            .magic(ImMessage.MAGIC_NUMBER)
            .version(ImMessage.WIRE_PROTOCOL_VERSION)
            .codecId(message.getCodecId())
            .cmd(CMD_PULL_RESP_VALUE)
            .messageId(message.getMessageId())
            .body(encodeProtobuf(resp))
            .build();

          sendResponse(connection, response);
        })
        .onFailure(e -> {
          LOG.error("拉取离线消息失败", e);
          sendErrorResponse(connection, message, 500, "拉取离线消息失败：" + e.getMessage());
        });
    } catch (Exception e) {
      LOG.error("处理拉取消息请求失败", e);
      sendErrorResponse(connection, message, 500, "处理拉取消息请求失败：" + e.getMessage());
    }
  }
}
