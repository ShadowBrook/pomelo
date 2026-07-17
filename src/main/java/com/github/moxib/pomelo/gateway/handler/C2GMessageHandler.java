package com.github.moxib.pomelo.gateway.handler;

import com.github.moxib.pomelo.codec.CodecRegistry;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.service.MessageRepository;
import com.github.moxib.pomelo.service.MessageService;
import com.github.moxib.pomelo.utils.IdGenerator;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 群聊消息处理器（当前为占位实现）。
 */
public class C2GMessageHandler extends AbstractMessageHandler {

  private static final Logger LOG = LoggerFactory.getLogger(C2GMessageHandler.class);

  public C2GMessageHandler(Vertx vertx, CodecRegistry codecRegistry,
                            SessionRegistry sessionRegistry, MessageRepository messageRepo,
                            MessageService messageService, IdGenerator idGenerator) {
    super(vertx, codecRegistry, sessionRegistry, messageRepo, messageService, idGenerator);
  }

  @Override
  public void handle(Connection connection, ImMessage message) {
    LOG.debug("收到群聊消息（暂未实现）");
    sendErrorResponse(connection, message, 501, "群聊功能尚未实现");
  }
}
