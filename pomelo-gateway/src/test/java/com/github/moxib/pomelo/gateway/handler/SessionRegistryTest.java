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
 * SessionRegistry 会话竞态回归测试（端型槽位制）。
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

    registry.register("u1", "web", 1L, oldConn, "alice", "Alice", null);
    SessionRegistry.Session evicted = registry.register("u1", "web", 1L, newConn, "alice", "Alice", null);

    SessionRegistry.Session removed = registry.unregisterByConnection(oldConn);

    ctx.verify(() -> {
      assertSame(oldConn, evicted.connection, "第二次注册应返回被顶掉的旧会话");
      assertNull(removed, "旧连接断开不应触发任何会话清理");
      assertSame(newConn, registry.getSession("u1", "web").connection, "新会话必须仍然在线");
      assertEquals("u1", registry.getUserIdByConnection(newConn));
      ctx.completeNow();
    });
  }

  @Test
  @DisplayName("会话持有者断开时正常清理会话")
  void ownerConnectionCloseRemovesSession(Vertx vertx, VertxTestContext ctx) {
    SessionRegistry registry = new SessionRegistry(vertx);
    FakeConnection conn = new FakeConnection();

    registry.register("u1", "web", 1L, conn, "alice", "Alice", null);
    SessionRegistry.Session removed = registry.unregisterByConnection(conn);

    ctx.verify(() -> {
      assertNotNull(removed);
      assertEquals("u1", removed.getUserId());
      assertNull(registry.getSession("u1", "web"));
      assertNull(registry.getUserIdByConnection(conn));
      ctx.completeNow();
    });
  }

  @Test
  @DisplayName("按端型槽位注销后重复断连事件为幂等 no-op")
  void unregisterIsIdempotent(Vertx vertx, VertxTestContext ctx) {
    SessionRegistry registry = new SessionRegistry(vertx);
    FakeConnection conn = new FakeConnection();

    registry.register("u1", "web", 1L, conn, "alice", "Alice", null);
    assertNotNull(registry.unregisterByConnection(conn));
    assertNull(registry.unregisterByConnection(conn), "第二次断连事件应直接返回 null");

    ctx.completeNow();
  }

  @Test
  @DisplayName("同端型二次登录占据同一槽位（互斥），不同端型共存")
  void platformSlots(Vertx vertx, VertxTestContext ctx) {
    SessionRegistry registry = new SessionRegistry(vertx);
    FakeConnection webConn = new FakeConnection();
    FakeConnection webConn2 = new FakeConnection();
    FakeConnection androidConn = new FakeConnection();

    registry.register("u1", "web", 1L, webConn, "alice", "Alice", null);
    // 大小写与空白规范化到同一槽位
    SessionRegistry.Session evicted =
      registry.register("u1", " WEB ", 1L, webConn2, "alice", "Alice", null);
    registry.register("u1", "android", 1L, androidConn, "alice", "Alice", null);

    ctx.verify(() -> {
      assertSame(webConn, evicted.connection, "同端型（规范化后）二次注册应顶掉旧会话");
      assertEquals(2, registry.getSessionsByUserId("u1").size(), "web/android 应各占一个槽位");
      assertSame(webConn2, registry.getSession("u1", "web").connection);
      assertSame(androidConn, registry.getSession("u1", "android").connection);
      // 未知端型归入 unknown 槽位
      FakeConnection unknownConn = new FakeConnection();
      registry.register("u1", "", 1L, unknownConn, "alice", "Alice", null);
      assertSame(unknownConn, registry.getSession("u1", "unknown").connection);
      assertEquals(3, registry.getSessionsByUserId("u1").size());
      ctx.completeNow();
    });
  }

  @Test
  @DisplayName("注销会话后心跳定时器被取消，不再关闭已移除的连接")
  void unregisterCancelsHeartbeatTimer(Vertx vertx, VertxTestContext ctx) {
    SessionRegistry registry = new SessionRegistry(vertx);
    FakeConnection conn = new FakeConnection();

    SessionRegistry.Session session =
      registry.register("u1", "web", 1L, conn, "alice", "Alice", null);
    registry.startHeartbeatTimer(session, 50);
    registry.unregisterByConnection(conn);

    // 超过原定时器周期后连接不应被定时器关闭
    vertx.setTimer(200, id -> ctx.verify(() -> {
      assertFalse(conn.closed, "已注销会话的定时器不应再触发关闭");
      ctx.completeNow();
    }));
  }
}
