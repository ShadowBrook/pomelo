package com.github.moxib.pomelo.gateway;

import com.github.moxib.pomelo.common.ImMessage;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GatewayVerticle 测试
 */
@DisplayName("Gateway TCP 服务测试")
public class TcpGatewayVerticleTest {

  private Vertx vertx;
  private TcpGatewayVerticle tcpGatewayVerticle;
  private int tcpPort = 9000;

  @BeforeEach
  void setUp() throws InterruptedException {
    vertx = Vertx.vertx();
    tcpGatewayVerticle = new TcpGatewayVerticle();

    CountDownLatch latch = new CountDownLatch(1);
    vertx.deployVerticle(tcpGatewayVerticle)
      .onSuccess(deploymentId -> {
        System.out.println("Verticle deployed successfully");
        latch.countDown();
      })
      .onFailure(ar -> {
        System.err.println("Failed to deploy verticle: " + ar.getMessage());
        latch.countDown();
      });

    assertTrue(latch.await(5, TimeUnit.SECONDS));
    // 等待服务器完全启动
    Thread.sleep(500);
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    if (vertx != null) {
      CountDownLatch latch = new CountDownLatch(1);
      vertx.close().onSuccess(v -> latch.countDown());
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
  }

  @Test
  @DisplayName("测试 TCP 连接建立")
  void testTcpConnection() {
    System.out.println("Starting testTcpConnection...");
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean connected = new AtomicBoolean(false);

    NetClient client = vertx.createNetClient();
    client.connect(tcpPort, "localhost")
      .onSuccess(socket -> {
        System.out.println("Connected to server");
        connected.set(true);
        socket.close();
        latch.countDown();
      })
      .onFailure(ar -> {
        System.err.println("Failed to connect: " + ar.getMessage());
        latch.countDown();
      });

    try {
      assertTrue(latch.await(10, TimeUnit.SECONDS));
      assertTrue(connected.get());
    } catch (InterruptedException e) {
      fail("Test interrupted");
    }
  }

  @Test
  @DisplayName("测试心跳消息处理")
  void testHeartbeatMessage() {
    System.out.println("Starting testHeartbeatMessage...");
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean completed = new AtomicBoolean(false);

    NetClient client = vertx.createNetClient();
    client.connect(tcpPort, "localhost")
      .onSuccess(socket -> {
        System.out.println("Connected, sending heartbeat...");
        // 构建心跳消息
        ImMessage heartbeatRequest = ImMessage.builder()
          .version((byte) 1)
          .codecId((byte) 1)
          .cmd((byte) 0x01) // 心跳 cmd
          .messageId("heartbeat-001")
          .body("ping".getBytes(StandardCharsets.UTF_8))
          .build();

        // 发送心跳请求
        Buffer requestBuffer = heartbeatRequest.encodeToWire();
        System.out.println("Request buffer length: " + requestBuffer.length());
        socket.write(requestBuffer);

        // 等待并接收响应
        socket.handler(buffer -> {
          System.out.println("Received response, buffer length: " + buffer.length());
          ImMessage response = new ImMessage(true);
          response.readFromWire(buffer);

          // 验证响应
          System.out.println("Response cmd: " + response.getCmd());
          System.out.println("Response messageId: " + response.getMessageId());
          assertEquals((byte) 0x01, response.getCmd());
          assertEquals("heartbeat-001", response.getMessageId());

          completed.set(true);
          socket.close();
          latch.countDown();
        });
      })
      .onFailure(ar -> {
        System.err.println("Failed to connect: " + ar.getMessage());
        latch.countDown();
      });

    try {
      assertTrue(latch.await(10, TimeUnit.SECONDS));
      assertTrue(completed.get());
    } catch (InterruptedException e) {
      fail("Test interrupted");
    }
  }

  @Test
  @DisplayName("测试登录消息处理")
  void testLoginMessage() {
    System.out.println("Starting testLoginMessage...");
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean completed = new AtomicBoolean(false);

    NetClient client = vertx.createNetClient();
    client.connect(tcpPort, "localhost")
      .onSuccess(socket -> {
        System.out.println("Connected, sending login request...");
        // 构建登录请求
        ImMessage loginRequest = ImMessage.builder()
          .version((byte) 1)
          .codecId((byte) 1)
          .cmd((byte) 0x02) // 登录 cmd
          .messageId("login-001")
          .body("{\"username\":\"test\",\"password\":\"123456\"}".getBytes(StandardCharsets.UTF_8))
          .build();

        // 发送登录请求
        Buffer requestBuffer = loginRequest.encodeToWire();
        socket.write(requestBuffer);

        // 等待并接收响应
        socket.handler(buffer -> {
          System.out.println("Received login response");
          ImMessage response = new ImMessage(true);
          response.readFromWire(buffer);

          // 验证响应
          assertEquals((byte) 0x02, response.getCmd());
          assertEquals("login-001", response.getMessageId());

          completed.set(true);
          socket.close();
          latch.countDown();
        });
      })
      .onFailure(ar -> {
        System.err.println("Failed to connect: " + ar.getMessage());
        latch.countDown();
      });

    try {
      assertTrue(latch.await(10, TimeUnit.SECONDS));
      assertTrue(completed.get());
    } catch (InterruptedException e) {
      fail("Test interrupted");
    }
  }

  @Test
  @DisplayName("测试聊天消息处理")
  void testChatMessage() {
    System.out.println("Starting testChatMessage...");
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean completed = new AtomicBoolean(false);

    NetClient client = vertx.createNetClient();
    client.connect(tcpPort, "localhost")
      .onSuccess(socket -> {
        System.out.println("Connected, sending chat message...");
        // 构建聊天消息
        ImMessage chatRequest = ImMessage.builder()
          .version((byte) 1)
          .codecId((byte) 1)
          .cmd((byte) 0x10) // 聊天消息 cmd
          .messageId("chat-001")
          .body("你好，这是一条聊天消息".getBytes(StandardCharsets.UTF_8))
          .build();

        // 发送聊天消息
        Buffer requestBuffer = chatRequest.encodeToWire();
        socket.write(requestBuffer);

        // 等待并接收响应
        socket.handler(buffer -> {
          System.out.println("Received chat response");
          ImMessage response = new ImMessage(true);
          response.readFromWire(buffer);

          // 验证响应
          assertEquals((byte) 0x04, response.getCmd());
          assertEquals("chat-001", response.getMessageId());

          completed.set(true);
          socket.close();
          latch.countDown();
        });
      })
      .onFailure(ar -> {
        System.err.println("Failed to connect: " + ar.getMessage());
        latch.countDown();
      });

    try {
      assertTrue(latch.await(10, TimeUnit.SECONDS));
      assertTrue(completed.get());
    } catch (InterruptedException e) {
      fail("Test interrupted");
    }
  }

  @Test
  @DisplayName("测试未知 cmd 处理")
  void testUnknownCmdMessage() {
    System.out.println("Starting testUnknownCmdMessage...");
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean completed = new AtomicBoolean(false);

    NetClient client = vertx.createNetClient();
    client.connect(tcpPort, "localhost")
      .onSuccess(socket -> {
        System.out.println("Connected, sending unknown cmd...");
        // 构建未知 cmd 的消息
        ImMessage unknownRequest = ImMessage.builder()
          .version((byte) 1)
          .codecId((byte) 1)
          .cmd((byte) 0x99) // 未知的 cmd
          .messageId("unknown-001")
          .build();

        // 发送消息
        Buffer requestBuffer = unknownRequest.encodeToWire();
        socket.write(requestBuffer);

        // 等待并接收响应
        socket.handler(buffer -> {
          System.out.println("Received unknown cmd response");
          ImMessage response = new ImMessage(true);
          response.readFromWire(buffer);

          // 验证响应（应该收到错误响应）
          System.out.println("Response cmd: " + response.getCmd());
          assertTrue(response.getCmd() == (byte) 0xFF || response.getCmd() == (byte) 0xFE);

          completed.set(true);
          socket.close();
          latch.countDown();
        });
      })
      .onFailure(ar -> {
        System.err.println("Failed to connect: " + ar.getMessage());
        latch.countDown();
      });

    try {
      assertTrue(latch.await(10, TimeUnit.SECONDS));
      assertTrue(completed.get());
    } catch (InterruptedException e) {
      fail("Test interrupted");
    }
  }

  @Test
  @DisplayName("测试 TCP 粘包处理")
  void testTcpStickPackage() {
    System.out.println("Starting testTcpStickPackage...");
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean completed = new AtomicBoolean(false);

    NetClient client = vertx.createNetClient();
    client.connect(tcpPort, "localhost")
      .onSuccess(socket -> {
        System.out.println("Connected, sending stick package...");
        // 构建两个心跳消息
        ImMessage heartbeat1 = ImMessage.builder()
          .version((byte) 1)
          .codecId((byte) 1)
          .cmd((byte) 0x01)
          .messageId("heartbeat-stick-001")
          .body("ping1".getBytes(StandardCharsets.UTF_8))
          .build();

        ImMessage heartbeat2 = ImMessage.builder()
          .version((byte) 1)
          .codecId((byte) 1)
          .cmd((byte) 0x01)
          .messageId("heartbeat-stick-002")
          .body("ping2".getBytes(StandardCharsets.UTF_8))
          .build();

        // 将两个消息拼接在一起发送（模拟粘包）
        Buffer buffer1 = heartbeat1.encodeToWire();
        Buffer buffer2 = heartbeat2.encodeToWire();
        Buffer stickBuffer = Buffer.buffer().appendBuffer(buffer1).appendBuffer(buffer2);

        System.out.println("Stick buffer length: " + stickBuffer.length());
        socket.write(stickBuffer);

        AtomicInteger responseCount = new AtomicInteger(0);

        // 等待并接收响应
        socket.handler(buffer -> {
          int count = responseCount.incrementAndGet();
          System.out.println("Received stick package response #" + count);
          ImMessage response = new ImMessage(true);
          response.readFromWire(buffer);

          // 收到两个响应后完成测试
          if (count >= 2) {
            completed.set(true);
            socket.close();
            latch.countDown();
          }
        });
      })
      .onFailure(ar -> {
        System.err.println("Failed to connect: " + ar.getMessage());
        latch.countDown();
      });

    try {
      assertTrue(latch.await(10, TimeUnit.SECONDS));
      assertTrue(completed.get());
    } catch (InterruptedException e) {
      fail("Test interrupted");
    }
  }
}
