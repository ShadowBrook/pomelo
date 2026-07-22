package com.github.moxib.pomelo.gateway.handler;

import com.github.moxib.pomelo.codec.CodecRegistry;
import com.github.moxib.pomelo.codec.ProtobufCodec;
import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.proto.ctrl.CtrlProto;
import com.github.moxib.pomelo.service.MessageRepository;
import com.github.moxib.pomelo.service.MessageService;
import com.github.moxib.pomelo.service.model.requests.CtrlRequest;
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
      CtrlRequest req = decodeRequest(message, CtrlRequest.class);
      LOG.info("收到控制命令请求，ctrlType: {}", req.ctrlType());

      // TODO: 实现具体的控制命令处理

      byte codecId = message.getCodecId();
      Object respBody = codecId == ProtobufCodec.CODEC_ID
        ? CtrlProto.CtrlResp.newBuilder().setCode(0).setMessage("success").build()
        : jsonBody().put("code", 0).put("message", "success");

      ImMessage response = buildResponse(message, CMD_CTRL_RESP_VALUE, respBody);
      sendResponse(connection, response);
    } catch (Exception e) {
      LOG.error("处理控制命令请求失败", e);
      sendErrorResponse(connection, message, CMD_CTRL_RESP_VALUE, ErrorCode.INTERNAL_ERROR, "处理控制命令失败：" + e.getMessage());
    }
  }
}
