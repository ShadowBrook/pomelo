package com.github.moxib.pomelo.gateway.handler;

import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.proto.ctrl.CtrlProto;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.CMD_CTRL_RESP_VALUE;

/**
 * 控制命令请求处理器
 * 处理客户端的控制命令请求，如踢下线、强制登出、通知、同步等
 */
public class CtrlReqHandler extends AbstractMessageHandler {

  private static final Logger LOG = LoggerFactory.getLogger(CtrlReqHandler.class);

  public CtrlReqHandler(Vertx vertx) {
    super(vertx);
  }

  @Override
  public void handle(Connection connection, ImMessage message) {
    try {
      // 解码 Protobuf 消息
      CtrlProto.CtrlReq req = decodeProtobuf(message.getBody(), CtrlProto.CtrlReq.class);

      LOG.info("收到控制命令请求，ctrlType: {}", req.getCtrlType());

      // TODO: 实现具体的控制命令处理逻辑
      // 根据 CtrlType 处理不同的控制命令：
      // - CTRL_TYPE_KICK_OFFLINE: 踢下线
      // - CTRL_TYPE_FORCE_LOGOUT: 强制登出
      // - CTRL_TYPE_NOTIFY: 通知
      // - CTRL_TYPE_SYNC: 同步

      // 构建控制命令响应
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
