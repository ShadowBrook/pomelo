package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.CMD_C2G_RESP_VALUE;

public class C2GService extends ServiceBase {

  private static final Logger LOG = LoggerFactory.getLogger(C2GService.class);

  public Future<ImMessage> process(ImMessage message) {
    LOG.debug("收到群聊消息（暂未实现）");
    return Future.succeededFuture(buildErrorResp(message, CMD_C2G_RESP_VALUE, ErrorCode.NOT_IMPLEMENTED, "群聊功能尚未实现"));
  }
}
