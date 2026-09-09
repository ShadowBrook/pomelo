package com.github.moxib.pomelo.gateway;

import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.proto.common.CommonProto;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gateway TCP 服务测试。
 * 客户端侧按长度前缀做帧累积解析（处理响应粘包/分片），剥掉 4 字节前缀后再 readFromWire。
 */
@DisplayName("Gateway TCP 服务测试")
public class TcpGatewayVerticleTest {

  private static final int TCP_PORT = 19100;

  private Vertx vertx;
  private TcpGatewayVerticle tcpGatewayVerticle;

  @BeforeEach
  void setUp() throws InterruptedException {
    // Verticle 启动时配置尚未加载，端口走系统属性兜底
    System.setProperty("gateway.tcp.port", String.valueOf(TCP_PORT));
    vertx = Vertx.vertx();
    tcpGatewayVerticle = new TcpGatewayVerticle();

    CountDownLatch latch = new CountDownLatch(1);
    vertx.deployVerticle(tcpGatewayVerticle)
      .onSuccess(deploymentId -> latch.countDown())
      .onFailure(ar -> {
        System.err.println("Failed to deploy verticle: " + ar.getMessage());
        latch.countDown();
      });

    assertTrue(latch.await(5, TimeUnit.SECONDS));
    Thread.sleep(300);
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    System.clearProperty("gateway.tcp.port");
    if (vertx != null) {
      CountDownLatch latch = new CountDownLatch(1);
      vertx.close().onSuccess(v -> latch.countDown());
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
  }

  /**
   * 客户端侧帧读取器：按 [4 字节长度][载荷] 累积切帧，
   * 同时处理 TCP 粘包（一次收到多帧）与分片（一帧拆多次到达）。
   */
  static class FrameReader {
    private final List<ImMessage> frames = new ArrayList<>();
    private final CountDownLatch latch;
    private Buffer leftover = Buffer.buffer();

    FrameReader(int expected) {
      this.latch = new CountDownLatch(expected);
    }

    Handler<Buffer> handler() {
      return chunk -> {
        Buffer data = leftover.appendBuffer(chunk);
        int pos = 0;
        while (data.length() - pos >= 4) {
          int len = data.getInt(pos);
          if (len < ImMessage.MIN_FRAME_LENGTH || len > ImMessage.MAX_FRAME_SIZE
            || data.length() - pos - 4 < len) {
            break;
          }
          ImMessage msg = new ImMessage();
          msg.readFromWire(data.getBuffer(pos + 4, pos + 4 + len));
          frames.add(msg);
          pos += 4 + len;
          latch.countDown();
        }
        leftover = Buffer.buffer().appendBuffer(data, pos, data.length() - pos);
      };
    }

    boolean awaitFrames(long seconds) throws InterruptedException {
      return latch.await(seconds, TimeUnit.SECONDS);
    }

    List<ImMessage> frames() {
      return frames;
    }
  }

  private NetSocket connect() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<NetSocket> socketRef = new AtomicReference<>();
    NetClient client = vertx.createNetClient();
    client.connect(TCP_PORT, "localhost")
      .onSuccess(s -> {
        socketRef.set(s);
        latch.countDown();
      })
      .onFailure(ar -> {
        System.err.println("Failed to connect: " + ar.getMessage());
        latch.countDown();
      });
    assertTrue(latch.await(10, TimeUnit.SECONDS), "连接应建立");
    return socketRef.get();
  }

  private static ImMessage ping(String messageId) {
    return ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId((byte) 0)
      .cmd(CMD_PING_VALUE)
      .messageId(messageId)
      .build();
  }

  @Test
  @DisplayName("测试 TCP 连接建立与关闭")
  void testTcpConnection() throws InterruptedException {
    NetSocket socket = connect();
    CountDownLatch closed = new CountDownLatch(1);
    socket.closeHandler(v -> closed.countDown());
    socket.close();
    assertTrue(closed.await(10, TimeUnit.SECONDS), "连接应被关闭");
  }

  @Test
  @DisplayName("测试心跳消息处理 - PING 应答 PONG")
  void testHeartbeatMessage() throws InterruptedException {
    NetSocket socket = connect();
    FrameReader reader = new FrameReader(1);
    socket.handler(reader.handler());

    socket.write(ping("heartbeat-001").encodeToWire());
    assertTrue(reader.awaitFrames(10), "应收到 PONG 响应");

    ImMessage response = reader.frames().get(0);
    assertEquals(CMD_PONG_VALUE, response.getCmd());
    assertEquals("heartbeat-001", response.getMessageId());
    socket.close();
  }

  @Test
  @DisplayName("测试未认证业务命令被拒绝 - 返回 UNAUTHORIZED 错误")
  void testUnauthenticatedC2CRejected() throws Exception {
    NetSocket socket = connect();
    FrameReader reader = new FrameReader(1);
    socket.handler(reader.handler());

    // 未完成 AUTH_REQ 直接发送 C2C_REQ（并伪造 userId 头）
    ImMessage c2c = ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId((byte) 0)
      .cmd(CMD_C2C_REQ_VALUE)
      .messageId("c2c-001")
      .varHeaders(Map.of("userId", "42"))
      .build();
    socket.write(c2c.encodeToWire());

    assertTrue(reader.awaitFrames(10), "应收到错误响应");
    ImMessage response = reader.frames().get(0);
    assertEquals(CMD_ERROR_VALUE, response.getCmd());
    CommonProto.ErrorBody err = CommonProto.ErrorBody.parseFrom(response.getBody());
    assertEquals(ErrorCode.UNAUTHORIZED.getCode(), err.getCode());
    socket.close();
  }

  @Test
  @DisplayName("测试 TCP 粘包处理 - 两个 PING 应答两个 PONG")
  void testTcpStickPackage() throws InterruptedException {
    NetSocket socket = connect();
    FrameReader reader = new FrameReader(2);
    socket.handler(reader.handler());

    Buffer stickBuffer = Buffer.buffer()
      .appendBuffer(ping("stick-1").encodeToWire())
      .appendBuffer(ping("stick-2").encodeToWire());
    socket.write(stickBuffer);

    assertTrue(reader.awaitFrames(10), "粘包的两帧都应得到响应");
    assertEquals(2, reader.frames().size());
    assertEquals("stick-1", reader.frames().get(0).getMessageId());
    assertEquals("stick-2", reader.frames().get(1).getMessageId());
    socket.close();
  }

  @Test
  @DisplayName("测试超大帧长度声明 - 连接被服务端断开")
  void testOversizedFrameClosesConnection() throws InterruptedException {
    NetSocket socket = connect();
    CountDownLatch closed = new CountDownLatch(1);
    socket.closeHandler(v -> closed.countDown());

    // 长度前缀声明超过 MAX_FRAME_SIZE 的帧
    Buffer evil = Buffer.buffer().appendInt(ImMessage.MAX_FRAME_SIZE + 1);
    socket.write(evil);

    assertTrue(closed.await(10, TimeUnit.SECONDS),
      "服务端应断开声明非法长度的连接");
  }
}
