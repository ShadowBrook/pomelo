package com.github.moxib.pomelo.gateway.handler;

import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.model.PushCodec;
import com.github.moxib.pomelo.model.PushEnvelope;
import com.github.moxib.pomelo.proto.common.CommonProto;
import com.github.moxib.pomelo.proto.ctrl.CtrlProto;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.SocketAddress;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MessageDispatcher 身份信任链回归测试：
 * 未认证连接的业务命令必须被拒绝，已认证连接的身份头必须被规范化覆写。
 */
@DisplayName("MessageDispatcher 身份信任链测试")
@ExtendWith(VertxExtension.class)
public class MessageDispatcherTest {

  /** 捕获写出帧的空连接 */
  static class CapturingConnection implements Connection {
    final List<Buffer> written = new CopyOnWriteArrayList<>();
    volatile boolean closed;

    @Override
    public Future<Void> write(Buffer buffer) {
      written.add(buffer);
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

  private Vertx vertx;

  @BeforeEach
  void setUp(VertxTestContext ctx) {
    vertx = Vertx.vertx();
    ctx.completeNow();
  }

  @AfterEach
  void tearDown(VertxTestContext ctx) {
    vertx.close().onComplete(ar -> ctx.completeNow());
  }

  private static ImMessage parseFrame(Buffer frame) {
    ImMessage msg = new ImMessage();
    msg.readFromWire(frame.getBuffer(4, frame.length()));
    return msg;
  }

  @Test
  @DisplayName("未认证连接发送业务命令被拒绝并返回 UNAUTHORIZED")
  void unauthenticatedBusinessCmdIsRejected(VertxTestContext ctx) {
    SessionRegistry registry = new SessionRegistry(vertx);
    MessageDispatcher dispatcher = new MessageDispatcher(vertx, registry, 90000);
    CapturingConnection conn = new CapturingConnection();

    // 客户端伪造 userId 头发送 C2C_REQ
    ImMessage request = ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER).version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId((byte) 0).cmd(CMD_C2C_REQ_VALUE).messageId("m1")
      .varHeaders(Map.of("userId", "42"))
      .build();
    dispatcher.dispatch(conn, request);

    vertx.setTimer(100, id -> ctx.verify(() -> {
      assertEquals(1, conn.written.size(), "未认证请求不应被转发，只应收到一条错误响应");
      ImMessage resp = parseFrame(conn.written.get(0));
      assertEquals(CMD_ERROR_VALUE, resp.getCmd());
      CommonProto.ErrorBody err = CommonProto.ErrorBody.parseFrom(resp.getBody());
      assertEquals(ErrorCode.UNAUTHORIZED.getCode(), err.getCode());
      ctx.completeNow();
    }));
  }

  @Test
  @DisplayName("未认证连接的心跳正常放行")
  void unauthenticatedPingIsAllowed(VertxTestContext ctx) {
    SessionRegistry registry = new SessionRegistry(vertx);
    MessageDispatcher dispatcher = new MessageDispatcher(vertx, registry, 90000);
    CapturingConnection conn = new CapturingConnection();

    ImMessage ping = ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER).version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId((byte) 0).cmd(CMD_PING_VALUE).messageId("ping-1")
      .build();
    dispatcher.dispatch(conn, ping);

    vertx.setTimer(100, id -> ctx.verify(() -> {
      assertEquals(1, conn.written.size());
      ImMessage resp = parseFrame(conn.written.get(0));
      assertEquals(CMD_PONG_VALUE, resp.getCmd());
      assertEquals("ping-1", resp.getMessageId());
      ctx.completeNow();
    }));
  }

  @Test
  @DisplayName("已认证连接伪造 userId 头被规范化为会话身份")
  void authenticatedHeaderSpoofingIsNormalized(VertxTestContext ctx) {
    SessionRegistry registry = new SessionRegistry(vertx);
    MessageDispatcher dispatcher = new MessageDispatcher(vertx, registry, 90000);
    CapturingConnection conn = new CapturingConnection();

    // 以真实身份 111 注册会话
    registry.register("111", "web", 111L, conn, "alice", "Alice", null);

    // logic.c2c 侧校验收到的身份头，并回一个合法 C2CResp
    vertx.eventBus().<Buffer>consumer("logic.c2c", busMsg -> {
      Buffer frame = busMsg.body();
      ImMessage received = new ImMessage();
      received.readFromWire(frame.getBuffer(4, frame.length()));
      ctx.verify(() -> {
        assertEquals("111", received.getVarHeaders().get("userId"),
          "转发到 logic 的 userId 必须是认证身份");
        assertEquals("alice", received.getVarHeaders().get("userName"));
      });
      byte[] respBody = CommonProto.ErrorBody.newBuilder().setCode(0).build().toByteArray();
      busMsg.reply(ImMessage.builder()
        .magic(ImMessage.MAGIC_NUMBER).version(ImMessage.WIRE_PROTOCOL_VERSION)
        .codecId((byte) 0).cmd(CMD_C2C_RESP_VALUE).messageId("m1").body(respBody)
        .build().encodeToWire());
    });

    // 客户端试图以 999 的身份发送
    ImMessage request = ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER).version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId((byte) 0).cmd(CMD_C2C_REQ_VALUE).messageId("m1")
      .varHeaders(Map.of("userId", "999"))
      .build();
    dispatcher.dispatch(conn, request);

    vertx.setTimer(200, id -> ctx.completeNow());
  }

  @Test
  @DisplayName("顶号踢下线：旧连接先收 KICK_OFFLINE 通知，随后被关闭")
  void kickSendsNotifyThenCloses(VertxTestContext ctx) throws Exception {
    SessionRegistry registry = new SessionRegistry(vertx);
    MessageDispatcher dispatcher = new MessageDispatcher(vertx, registry, 90000);
    CountDownLatch done = new CountDownLatch(2);
    CapturingConnection oldConn = new CapturingConnection() {
      @Override
      public Future<Void> write(Buffer buffer) {
        written.add(buffer);
        done.countDown();
        return Future.succeededFuture();
      }

      @Override
      public void close() {
        closed = true;
        done.countDown();
      }
    };

    registry.register("u1", "web", 1L, oldConn, "alice", "Alice", null);
    dispatcher.kickLocal(registry.getSession("u1", "web"), MessageDispatcher.KICK_REASON);

    assertTrue(done.await(5, TimeUnit.SECONDS), "通知与断连都应发生");
    ctx.verify(() -> {
      assertTrue(oldConn.closed, "被踢连接应被关闭");
      assertEquals(1, oldConn.written.size());
      ImMessage notify = parseFrame(oldConn.written.get(0));
      assertEquals(CMD_CTRL_NOTIFY_VALUE, notify.getCmd());
      CtrlProto.CtrlNotify body = CtrlProto.CtrlNotify.parseFrom(notify.getBody());
      assertEquals(CtrlProto.CtrlType.CTRL_TYPE_KICK_OFFLINE, body.getCtrlType());
      assertFalse(body.getReason().isEmpty(), "应携带踢下线原因（客户端展示并跳登录页）");
      ctx.completeNow();
    });
  }

  @Test
  @DisplayName("推送投递：不带端型投给用户全部端会话，带端型只投对应会话")
  void pushDeliveredPerPlatform(VertxTestContext ctx) throws Exception {
    SessionRegistry registry = new SessionRegistry(vertx);
    new MessageDispatcher(vertx, registry, 90000);
    CountDownLatch both = new CountDownLatch(2);
    CapturingConnection webConn = new CapturingConnection() {
      @Override
      public Future<Void> write(Buffer buffer) {
        written.add(buffer);
        both.countDown();
        return Future.succeededFuture();
      }
    };
    CapturingConnection androidConn = new CapturingConnection() {
      @Override
      public Future<Void> write(Buffer buffer) {
        written.add(buffer);
        both.countDown();
        return Future.succeededFuture();
      }
    };
    registry.register("u1", "web", 1L, webConn, "alice", "Alice", null);
    registry.register("u1", "android", 1L, androidConn, "alice", "Alice", null);

    byte[] body = CommonProto.ErrorBody.newBuilder().setCode(0).build().toByteArray();
    // 不带端型：全部端会话各收一份
    vertx.eventBus().send("gateway.push", PushCodec.encode(new PushEnvelope("u1", CMD_C2C_NOTIFY_VALUE, body)));
    assertTrue(both.await(5, TimeUnit.SECONDS), "不带端型应投递给全部端会话");
    assertEquals(1, webConn.written.size());
    assertEquals(1, androidConn.written.size());

    // 带 web 端型：只有 web 会话再收一份
    CountDownLatch webOnly = new CountDownLatch(1);
    CapturingConnection webConn2 = new CapturingConnection() {
      @Override
      public Future<Void> write(Buffer buffer) {
        written.add(buffer);
        webOnly.countDown();
        return Future.succeededFuture();
      }
    };
    // 替换注册表里 web 槽位（模拟 web 重连后的新连接）
    registry.register("u1", "web", 1L, webConn2, "alice", "Alice", null);
    PushEnvelope targeted = new PushEnvelope("u1", CMD_C2C_NOTIFY_VALUE, body);
    targeted.setTargetPlatform("web");
    vertx.eventBus().send("gateway.push", PushCodec.encode(targeted));
    assertTrue(webOnly.await(5, TimeUnit.SECONDS), "web 会话应收到定向推送");
    ctx.verify(() -> {
      assertEquals(1, webConn2.written.size(), "web 新会话只应收到定向推送这一条");
      assertEquals(1, androidConn.written.size(), "android 会话不应收到 web 定向推送");
      ctx.completeNow();
    });
  }
}
