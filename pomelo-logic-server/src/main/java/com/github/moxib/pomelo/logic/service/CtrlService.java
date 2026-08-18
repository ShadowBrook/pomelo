package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.codec.CodecRegistry;
import com.github.moxib.pomelo.codec.ProtobufCodec;
import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.logic.model.requests.CtrlRequest;
import com.github.moxib.pomelo.proto.ctrl.CtrlProto;
import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.*;

public class CtrlService extends ServiceBase {

  private static final Logger LOG = LoggerFactory.getLogger(CtrlService.class);

  private final CodecRegistry codecRegistry;

  public CtrlService() {
    this.codecRegistry = new CodecRegistry();
    codecRegistry.registerProtobuf(CMD_CTRL_REQ_VALUE, CtrlProto.CtrlReq.parser(), CtrlRequest::fromProto, CtrlRequest.class);
    codecRegistry.registerJson(CMD_CTRL_REQ_VALUE, CtrlRequest.class);
  }

  public Future<ImMessage> process(ImMessage message) {
    try {
      CtrlRequest req = decode(codecRegistry, message, CtrlRequest.class);
      LOG.info("收到控制命令请求，ctrlType: {}", req.ctrlType());

      byte codecId = message.getCodecId();
      Object respBody = codecId == ProtobufCodec.CODEC_ID
        ? CtrlProto.CtrlResp.newBuilder().setCode(0).setMessage("success").build()
        : jsonBody().put("code", 0).put("message", "success");

      return Future.succeededFuture(buildResponse(message, CMD_CTRL_RESP_VALUE, respBody));
    } catch (Exception e) {
      LOG.error("处理控制命令请求失败", e);
      return Future.succeededFuture(buildErrorResp(message, CMD_CTRL_RESP_VALUE, ErrorCode.INTERNAL_ERROR, "处理控制命令失败：" + e.getMessage()));
    }
  }
}
