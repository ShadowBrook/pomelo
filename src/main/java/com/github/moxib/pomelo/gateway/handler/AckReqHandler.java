package com.github.moxib.pomelo.gateway.handler;

import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.proto.ack.AckProto;
import com.github.moxib.pomelo.proto.common.CommonProto;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.CMD_ACK_RESP_VALUE;

/**
 * ACK 消息确认请求处理器
 * 处理客户端的消息确认请求，用于可靠消息传输
 */
public class AckReqHandler extends AbstractMessageHandler {

  private static final Logger LOG = LoggerFactory.getLogger(AckReqHandler.class);

  public AckReqHandler(Vertx vertx) {
    super(vertx);
  }

  @Override
  public void handle(Connection connection, ImMessage message) {
    try {
      // 解码 Protobuf 消息
      AckProto.AckReq req = decodeProtobuf(message.getBody(), AckProto.AckReq.class);

      LOG.info("收到 ACK 确认请求，messageIds 数量：{}, ackType: {}",
        req.getMessageIdsCount(), req.getAckType());

      // TODO: 实现具体的 ACK 处理逻辑
      // 根据 messageIds 列表更新已读状态
      // ackType 可能表示不同的确认类型（如：已接收、已读等）

      // 构建 ACK 响应
      AckProto.AckResp resp = AckProto.AckResp.newBuilder()
        .setAckType(CommonProto.AckType.RECEIVED)
        .build();

      ImMessage response = ImMessage.builder()
        .magic(ImMessage.MAGIC_NUMBER)
        .version(ImMessage.WIRE_PROTOCOL_VERSION)
        .codecId(message.getCodecId())
        .cmd(CMD_ACK_RESP_VALUE)
        .messageId(message.getMessageId())
        .body(encodeProtobuf(resp))
        .build();

      sendResponse(connection, response);
    } catch (Exception e) {
      LOG.error("处理 ACK 确认请求失败", e);
      sendErrorResponse(connection, message, 500, "处理 ACK 确认失败：" + e.getMessage());
    }
  }
}
