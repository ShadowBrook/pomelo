package com.github.moxib.pomelo.benchmark;

import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.proto.auth.AuthProto;
import com.github.moxib.pomelo.proto.chat.ChatProto;
import com.github.moxib.pomelo.proto.common.CommonProto;
import com.github.moxib.pomelo.proto.relation.RelationProto;
import com.google.protobuf.ByteString;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.parsetools.RecordParser;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.*;

/**
 * TCP IM 客户端 — 复用 ImMessage 序列化，走真实 TCP 网关。
 * 粘包处理与 TcpGatewayVerticle 对称（4 字节长度前缀 + wire body）。
 * 共享单个 NetClient（Vert.x 推荐复用，避免每次连接创建实例导致并发资源耗尽）。
 * 请求 body 一律为 Protobuf（codecId 冻结为 0）。
 */
public class ImClient {

  /** 单请求超时：连接被服务端回收（半开 TCP，收不到 FIN）时避免请求永久悬挂 */
  private static final long REQUEST_TIMEOUT_MS = 30_000;

  /** 心跳间隔：网关按 gateway.idleTimeoutSeconds(120s) 回收不活跃连接，客户端 30s 保活一次 */
  private static final long PING_INTERVAL_MS = 30_000;

  /** 心跳响应超时：PONG 迟迟不回即认为连接已死（半开连接不会给 FIN，只能靠探活） */
  private static final long PING_TIMEOUT_MS = 10_000;

  /** 默认端型：必须是 SessionRouteTable 的已知槽位，否则推送全部 no_route 丢弃 */
  private static final String PLATFORM_DEFAULT = "web";

  private final Vertx vertx;
  private final NetSocket socket;
  private final Map<String, CompletableFuture<ImMessage>> pending = new ConcurrentHashMap<>();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final long pingTimerId;
  // 明文帧字节数（未加密）：用于换算线上带宽占用，对端方向即服务端出/入带宽
  private final LongAdder bytesIn = new LongAdder();
  private final LongAdder bytesOut = new LongAdder();
  private final LongAdder bytesInResp = new LongAdder();
  private final LongAdder bytesInPush = new LongAdder();
  private final LongAdder pushFrames = new LongAdder();
  private final LongAdder respFrames = new LongAdder();
  private final LongAdder framesOut = new LongAdder();
  private final String platform;
  private final int messageSize;

  private ImClient(Vertx vertx, NetSocket socket, String platform, int messageSize) {
    this.vertx = vertx;
    this.socket = socket;
    this.platform = platform;
    this.messageSize = messageSize;
    setupParser(socket);
    // 服务端断开必须让在途请求立刻失败：否则被回收的连接会静默卡住整条并发流水线
    // 只在「不是自己关的」时候打日志，否则收尾阶段每个客户端都会刷一行对端关闭
    socket.closeHandler(v -> {
      boolean selfInitiated = closed.getAndSet(true);
      if (!selfInitiated) {
        System.err.println("连接被对端关闭（FIN/RST）");
      }
      failAllPending("连接已被服务端关闭");
    });
    socket.exceptionHandler(e -> {
      closed.set(true);
      System.err.println("连接异常: " + e.getMessage());
      failAllPending("连接异常: " + e.getMessage());
    });
    pingTimerId = vertx.setPeriodic(PING_INTERVAL_MS, id -> keepalive());
  }

  public static Future<ImClient> connect(Vertx vertx, String host, int port, boolean tls) {
    return connect(vertx, host, port, tls, PLATFORM_DEFAULT, 0);
  }

  /**
   * tls=true 时以 TLS 握手连接网关（服务端 tls.enabled=true 时必须开启）。
   * platform 必须是已知端型槽位（web/android/ios/unknown）：推送经
   * SessionRouteTable.resolveAll 按这四个槽位扇出，用别的名字注册的路由收不到任何推送。
   */
  public static Future<ImClient> connect(Vertx vertx, String host, int port, boolean tls, String platform,
                                       int messageSize) {
    NetClient client = SharedNetClient.get(vertx, tls);
    return client.connect(port, host).map(socket -> new ImClient(vertx, socket, platform, messageSize));
  }

  /**
   * 客户端心跳 + 存活探测。网关对已认证连接有心跳超时，空转连接会被关闭；
   * 更重要的是：半开连接（对端消失但无 FIN）只能靠探活发现——发现即判定失活，
   * 让在途请求立刻失败、后续请求直接快失败，否则每条请求都要白等满超时才释放并发槽位。
   */
  private void keepalive() {
    if (closed.get()) {
      return;
    }
    String messageId = "bench-ping-" + UUID.randomUUID();
    ImMessage ping = ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId((byte) 0)
      .cmd(CMD_PING_VALUE)
      .messageId(messageId)
      .build();
    CompletableFuture<ImMessage> pong = new CompletableFuture<>();
    pending.put(messageId, pong);
    long timerId = vertx.setTimer(PING_TIMEOUT_MS, id -> {
      CompletableFuture<ImMessage> expired = pending.remove(messageId);
      if (expired != null) {
        expired.completeExceptionally(new TimeoutException("心跳无响应 " + PING_TIMEOUT_MS + "ms"));
      }
    });
    pong.whenComplete((resp, err) -> {
      vertx.cancelTimer(timerId);
      if (err != null) {
        closed.set(true);
        System.err.printf("连接失活（%s）：在途请求立即失败，后续请求不再发送%n", err.getMessage());
        failAllPending("连接失活");
      }
    });
    write(ping);
  }

  private void setupParser(NetSocket socket) {
    RecordParser parser = RecordParser.newFixed(4);
    int[] size = {-1};
    parser.setOutput(buff -> {
      int frameBytes = buff.length();
      bytesIn.add(frameBytes);
      if (size[0] == -1) {
        size[0] = buff.getInt(0);
        parser.fixedSizeMode(size[0]);
      } else {
        ImMessage msg = new ImMessage();
        msg.readFromWire(buff);
        parser.fixedSizeMode(4);
        size[0] = -1;
        if (onMessage(msg)) {
          bytesInResp.add(frameBytes);
          respFrames.increment();
        } else {
          // 无 pending 匹配 = 推送（C2C_NOTIFY / 回声等），messageId 通常为空
          bytesInPush.add(frameBytes);
          pushFrames.increment();
        }
      }
    });
    socket.handler(parser);
  }

  /** @return true=命中在途请求（响应），false=无匹配（推送帧） */
  private boolean onMessage(ImMessage msg) {
    String messageId = msg.getMessageId();
    if (messageId == null || messageId.isEmpty()) {
      return false;
    }
    CompletableFuture<ImMessage> future = pending.remove(messageId);
    if (future == null) {
      return false;
    }
    future.complete(msg);
    return true;
  }

  Future<ImMessage> request(int cmd, com.google.protobuf.Message body, Map<String, String> headers) {
    if (closed.get()) {
      return Future.failedFuture("连接已关闭");
    }
    String messageId = "bench-" + UUID.randomUUID();
    ImMessage msg = ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId((byte) 0)
      .cmd(cmd)
      .messageId(messageId)
      .body(body.toByteArray())
      .varHeaders(headers)
      .build();
    CompletableFuture<ImMessage> future = new CompletableFuture<>();
    pending.put(messageId, future);
    long timerId = vertx.setTimer(REQUEST_TIMEOUT_MS, id -> {
      CompletableFuture<ImMessage> expired = pending.remove(messageId);
      if (expired != null) {
        expired.completeExceptionally(
          new TimeoutException("请求无响应 " + REQUEST_TIMEOUT_MS + "ms: cmd=0x" + Integer.toHexString(cmd)));
      }
    });
    future.whenComplete((r, e) -> vertx.cancelTimer(timerId));
    write(msg);
    return Future.fromCompletionStage(future);
  }

  private void write(ImMessage msg) {
    Buffer wire = msg.encodeToWire();
    bytesOut.add(wire.length());
    framesOut.increment();
    socket.write(wire);
  }

  /** 本连接收到的明文字节数（即服务端出带宽方向） */
  long bytesIn() {
    return bytesIn.sum();
  }

  /** 本连接发出的明文字节数（即服务端入带宽方向） */
  long bytesOut() {
    return bytesOut.sum();
  }

  /** 收到的响应帧明文字节数 */
  long bytesInResp() {
    return bytesInResp.sum();
  }

  /** 收到的推送帧明文字节数与帧数 */
  long bytesInPush() {
    return bytesInPush.sum();
  }

  long pushFrames() {
    return pushFrames.sum();
  }

  long respFrames() {
    return respFrames.sum();
  }

  long framesOut() {
    return framesOut.sum();
  }

  Future<ImMessage> login(String token, String userId, String userName) {
    AuthProto.AuthReq body = AuthProto.AuthReq.newBuilder()
      .setToken(token)
      .setDeviceId("bench")
      .setPlatform(platform)
      .setAppVersion("1.0.0")
      .build();
    return request(CMD_AUTH_REQ_VALUE, body, Map.of());
  }

  Future<ImMessage> addFriend(String selfId, String friendId) {
    RelationProto.FriendAddReq body = RelationProto.FriendAddReq.newBuilder()
      .setUserId(Long.parseLong(selfId)).setFriendId(Long.parseLong(friendId)).build();
    return request(CMD_FRIEND_ADD_REQ_VALUE, body, Map.of("userId", selfId));
  }

  Future<ImMessage> acceptFriend(String selfId, String friendId) {
    RelationProto.FriendAcceptReq body = RelationProto.FriendAcceptReq.newBuilder()
      .setUserId(Long.parseLong(selfId)).setFriendId(Long.parseLong(friendId)).build();
    return request(CMD_FRIEND_ACCEPT_REQ_VALUE, body, Map.of("userId", selfId));
  }

  Future<ImMessage> sendMessage(String selfId, String selfName, String peerId, String content) {
    // 正文长度对齐到 messageSize：用于把“字节速率受限”与“服务端工作量受限”分离
    if (messageSize > content.length()) {
      content = content + "x".repeat(messageSize - content.length());
    }
    CommonProto.MessageContent message = CommonProto.MessageContent.newBuilder()
      .setMsgTypeValue(1)
      .setContent(ByteString.copyFromUtf8(content))
      .build();
    ChatProto.C2CReq body = ChatProto.C2CReq.newBuilder()
      .setSenderId(Long.parseLong(selfId))
      .setRecipientId(Long.parseLong(peerId))
      .setMessage(message)
      .build();
    Map<String, String> headers = Map.of(
      "userId", selfId,
      "userName", selfName,
      "nickname", selfName);
    return request(CMD_C2C_REQ_VALUE, body, headers);
  }

  void close() {
    closed.set(true);
    vertx.cancelTimer(pingTimerId);
    failAllPending("连接已关闭");
    socket.close();
  }

  /** 连接不可用时让所有在途请求立即失败，避免调用方永远等不到结果 */
  private void failAllPending(String reason) {
    for (String messageId : pending.keySet()) {
      CompletableFuture<ImMessage> future = pending.remove(messageId);
      if (future != null) {
        future.completeExceptionally(new IllegalStateException(reason));
      }
    }
  }

  /** 共享 NetClient：Vert.x 推荐一个实例管理多个连接，避免并发连接时每次创建耗尽资源（按是否 TLS 各一个） */
  private static final class SharedNetClient {
    private static volatile NetClient plain;
    private static volatile NetClient tls;

    static NetClient get(Vertx vertx, boolean useTls) {
      if (useTls) {
        if (tls == null) {
          synchronized (SharedNetClient.class) {
            if (tls == null) {
              // 压测场景接受自签名证书；trustAll 时必须同时关闭主机名校验，
              // 否则 Vert.x 以 "Missing hostname verification algorithm" 拒绝建连
              tls = vertx.createNetClient(new NetClientOptions()
                .setSsl(true).setTrustAll(true).setHostnameVerificationAlgorithm(""));
            }
          }
        }
        return tls;
      }
      if (plain == null) {
        synchronized (SharedNetClient.class) {
          if (plain == null) {
            plain = vertx.createNetClient();
          }
        }
      }
      return plain;
    }
  }
}
