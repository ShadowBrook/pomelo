package com.github.moxib.pomelo.gateway.handler;

import com.github.moxib.pomelo.codec.CodecRegistry;
import com.github.moxib.pomelo.codec.ProtobufCodec;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.proto.pull.PullProto;
import com.github.moxib.pomelo.service.MessageRepository;
import com.github.moxib.pomelo.service.MessageService;
import com.github.moxib.pomelo.utils.IdGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.CMD_PULL_RESP_VALUE;

/**
 * 离线消息拉取处理器。
 * 用户上线后通过 PullReq 拉取未完全送达的消息（status < 2）。
 */
public class PullMessageHandler extends AbstractMessageHandler {

  private static final Logger LOG = LoggerFactory.getLogger(PullMessageHandler.class);

  public PullMessageHandler(Vertx vertx, CodecRegistry codecRegistry,
                             SessionRegistry sessionRegistry, MessageRepository messageRepo,
                             MessageService messageService, IdGenerator idGenerator) {
    super(vertx, codecRegistry, sessionRegistry, messageRepo, messageService, idGenerator);
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
        Map<String, String> headers = message.getVarHeaders();
        userId = headers != null ? headers.get("userId") : null;
      } else {
        JsonNode json = parseJsonBody(message);
        userId = json.get("userId").asText();
        sinceSeq = json.has("lastMsgId") ? json.get("lastMsgId").asLong() : 0;
        limit = json.has("limit") ? json.get("limit").asInt() : 50;
      }

      if (userId == null || userId.isEmpty()) {
        sendErrorResponse(connection, message, 401, "未认证用户");
        return;
      }

      LOG.info("拉取消息请求: userId={} sinceSeq={} limit={}", userId, sinceSeq, limit);

      messageService.pullOfflineMessages(userId, sinceSeq, limit)
        .onSuccess(records -> {
          PullProto.PullResp.Builder respBuilder = PullProto.PullResp.newBuilder()
            .setCode(0)
            .setMessage("success")
            .setHasMore(records != null && records.size() >= limit);

          ImMessage response = ImMessage.builder()
            .magic(ImMessage.MAGIC_NUMBER)
            .version(ImMessage.WIRE_PROTOCOL_VERSION)
            .codecId(message.getCodecId())
            .cmd(CMD_PULL_RESP_VALUE)
            .messageId(message.getMessageId())
            .body(encodeProtobuf(respBuilder.build()))
            .build();

          sendResponse(connection, response);
        })
        .onFailure(e -> {
          LOG.error("拉取离线消息失败", e);
          sendErrorResponse(connection, message, 500, "拉取失败：" + e.getMessage());
        });

    } catch (Exception e) {
      LOG.error("处理拉取请求失败", e);
      sendErrorResponse(connection, message, 500, "处理失败：" + e.getMessage());
    }
  }
}
