package com.github.moxib.pomelo.gateway.handler;

import com.github.moxib.pomelo.codec.CodecRegistry;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.proto.ctrl.CtrlProto;
import com.github.moxib.pomelo.service.MessageRepository;
import com.github.moxib.pomelo.service.MessageService;
import com.github.moxib.pomelo.utils.IdGenerator;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.CMD_CTRL_RESP_VALUE;

/**
 * 控制命令请求处理器。
 */
public class CtrlReqHandler extends AbstractMessageHandler {

  private static final Logger LOG = LoggerFactory.getLogger(CtrlReqHandler.class);

  public CtrlReqHandler(Vertx vertx, CodecRegistry codecRegistry,
                         SessionRegistry sessionRegistry, MessageRepository messageRepo,
                         MessageService messageService, IdGenerator idGenerator) {
    super(vertx, codecRegistry, sessionRegistry, messageRepo, messageService, idGenerator);
  }

  @Override
  public void handle(Connection connection, ImMessage message) {
    try {
      CtrlProto.CtrlReq req = decodeBody(message);
      LOG.info("收到控制命令请求，ctrlType: {}", req.getCtrlType());

      // TODO: 实现具体的控制命令处理

      CtrlProto.CtrlResp resp = CtrlProto.CtrlResp.newBuilder()
        .setCode(0)
        .setMessage("success")
        .build();

      ImMessage response = ImMessage.builder()
        .magic(ImMessage.MAGIC_NUMBER)
        .version(ImMessage.WIRE_PROTOCOL_VERSION)
        .codecId(message.getCodecId())
        .cmd(CMD_CTRL_RESP_VALUE)
        .messageId(message.getMessageId())
        .body(encodeProtobuf(resp))
        .build();

      sendResponse(connection, response);
    } catch (Exception e) {
      LOG.error("处理控制命令请求失败", e);
      sendErrorResponse(connection, message, 500, "处理控制命令失败：" + e.getMessage());
    }
  }
}
