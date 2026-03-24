package com.github.moxib.pomelo.gateway.handler;

import com.github.moxib.pomelo.common.ImMessage;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class C2GMessageHandler extends AbstractMessageHandler {

  private static final Logger LOG = LoggerFactory.getLogger(C2GMessageHandler.class);

  public C2GMessageHandler(Vertx vertx) {
    super(vertx);
  }

  @Override
  public void handle(Connection connection, ImMessage message) {

  }
}
