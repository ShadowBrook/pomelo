package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.common.ImMessage;
import io.vertx.core.Future;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.CMD_PONG_VALUE;

public class HeartbeatService extends ServiceBase {

  public Future<ImMessage> process(ImMessage message) {
    ImMessage pong = ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId(message.getCodecId())
      .cmd(CMD_PONG_VALUE)
      .messageId(message.getMessageId())
      .build();
    return Future.succeededFuture(pong);
  }
}
