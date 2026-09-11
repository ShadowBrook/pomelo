package com.github.moxib.pomelo.gateway.handler;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.SocketAddress;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 未认证连接的超时回收。
 * <p>
 * 心跳定时器只在认证成功后启动，未认证连接此前既无读超时也无业务约束，
 * 建立后不发数据即可无限期占用 FD/内存。
 */
@ExtendWith(VertxExtension.class)
@DisplayName("未认证连接认证截止测试")
class SessionRegistryAuthDeadlineTest {

  private static final long DEADLINE_MS = 50;
  private static final long OBSERVE_MS = 300;

  @Test
  @DisplayName("超时未认证的连接被关闭")
  void closesConnectionThatNeverAuthenticates(Vertx vertx, VertxTestContext ctx) {
    SessionRegistry registry = new SessionRegistry(vertx);
    FakeConnection conn = new FakeConnection();
    registry.startAuthDeadline(conn, DEADLINE_MS);

    vertx.setTimer(OBSERVE_MS, id -> ctx.verify(() -> {
      assertTrue(conn.closed.get(), "认证截止到点应关闭连接");
      ctx.completeNow();
    }));
  }

  @Test
  @DisplayName("认证成功后不再触发认证截止")
  void authenticatedConnectionSurvivesDeadline(Vertx vertx, VertxTestContext ctx) {
    SessionRegistry registry = new SessionRegistry(vertx);
    FakeConnection conn = new FakeConnection();
    registry.startAuthDeadline(conn, DEADLINE_MS);
    registry.register("100", 100L, conn, "u", "n");

    vertx.setTimer(OBSERVE_MS, id -> ctx.verify(() -> {
      assertFalse(conn.closed.get(), "认证成功应取消认证截止定时器");
      ctx.completeNow();
    }));
  }

  @Test
  @DisplayName("认证前断连即取消定时器，不再对已关闭连接动手")
  void unregisterCancelsDeadline(Vertx vertx, VertxTestContext ctx) {
    SessionRegistry registry = new SessionRegistry(vertx);
    FakeConnection conn = new FakeConnection();
    registry.startAuthDeadline(conn, DEADLINE_MS);
    registry.unregisterByConnection(conn);

    vertx.setTimer(OBSERVE_MS, id -> ctx.verify(() -> {
      assertFalse(conn.closed.get(), "已注销连接不应再被认证截止关闭");
      ctx.completeNow();
    }));
  }

  /** 只关心 close 是否被调用 */
  private static final class FakeConnection implements Connection {

    private final AtomicBoolean closed = new AtomicBoolean(false);

    @Override
    public Future<Void> write(Buffer buffer) {
      return Future.succeededFuture();
    }

    @Override
    public SocketAddress remoteAddress() {
      return SocketAddress.inetSocketAddress(12345, "127.0.0.1");
    }

    @Override
    public void close() {
      closed.set(true);
    }
  }
}
