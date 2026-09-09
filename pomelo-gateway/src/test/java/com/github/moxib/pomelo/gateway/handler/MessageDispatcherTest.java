package com.github.moxib.pomelo.gateway.handler;

import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.proto.common.CommonProto;
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
    registry.register("111", 111L, conn, "alice", "Alice", null);

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
}
