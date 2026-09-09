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

import static org.junit.jupiter.api.Assertions.*;

/**
 * SessionRegistry 会话竞态回归测试。
 */
@DisplayName("SessionRegistry 会话管理测试")
@ExtendWith(VertxExtension.class)
public class SessionRegistryTest {

  /** 仅记录 close 调用的空连接 */
  static class FakeConnection implements Connection {
    boolean closed;

    @Override
    public Future<Void> write(Buffer buffer) {
      return Future.succeededFuture();
    }

    @Override
    public SocketAddress remoteAddress() {
      return null;
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  @Test
  @DisplayName("顶号后旧连接迟到的断连事件不得误删新会话")
  void staleConnectionCloseKeepsNewSession(Vertx vertx, VertxTestContext ctx) {
    SessionRegistry registry = new SessionRegistry(vertx);
    FakeConnection oldConn = new FakeConnection();
    FakeConnection newConn = new FakeConnection();

    registry.register("u1", 1L, oldConn, "alice", "Alice", null);
    registry.register("u1", 1L, newConn, "alice", "Alice", null);

    String removed = registry.unregisterByConnection(oldConn);

    ctx.verify(() -> {
      assertNull(removed, "旧连接断开不应触发任何会话清理");
      assertSame(newConn, registry.getConnectionByUserId("u1"), "新会话必须仍然在线");
      assertEquals("u1", registry.getUserIdByConnection(newConn));
      ctx.completeNow();
    });
  }

  @Test
  @DisplayName("会话持有者断开时正常清理会话")
  void ownerConnectionCloseRemovesSession(Vertx vertx, VertxTestContext ctx) {
    SessionRegistry registry = new SessionRegistry(vertx);
    FakeConnection conn = new FakeConnection();

    registry.register("u1", 1L, conn, "alice", "Alice", null);
    String removed = registry.unregisterByConnection(conn);

    ctx.verify(() -> {
      assertEquals("u1", removed);
      assertNull(registry.getConnectionByUserId("u1"));
      assertNull(registry.getUserIdByConnection(conn));
      ctx.completeNow();
    });
  }

  @Test
  @DisplayName("按 userId 注销后重复断连事件为幂等 no-op")
  void unregisterIsIdempotent(Vertx vertx, VertxTestContext ctx) {
    SessionRegistry registry = new SessionRegistry(vertx);
    FakeConnection conn = new FakeConnection();

    registry.register("u1", 1L, conn, "alice", "Alice", null);
    assertEquals("u1", registry.unregisterByConnection(conn));
    assertNull(registry.unregisterByConnection(conn), "第二次断连事件应直接返回 null");

    ctx.completeNow();
  }

  @Test
  @DisplayName("注销会话后心跳定时器被取消，不再关闭已移除的连接")
  void unregisterCancelsHeartbeatTimer(Vertx vertx, VertxTestContext ctx) {
    SessionRegistry registry = new SessionRegistry(vertx);
    FakeConnection conn = new FakeConnection();

    registry.register("u1", 1L, conn, "alice", "Alice", null);
    registry.startHeartbeatTimer("u1", 50);
    registry.unregisterByConnection(conn);

    // 超过原定时器周期后连接不应被定时器关闭
    vertx.setTimer(200, id -> ctx.verify(() -> {
      assertFalse(conn.closed, "已注销会话的定时器不应再触发关闭");
      ctx.completeNow();
    }));
  }
}
