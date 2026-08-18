package com.github.moxib.pomelo.benchmark;

import com.github.moxib.pomelo.common.ImMessage;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;
import io.vertx.core.parsetools.RecordParser;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.*;

/**
 * TCP IM 客户端 — 复用 ImMessage 序列化，走真实 TCP 网关。
 * 粘包处理与 TcpGatewayVerticle 对称（4 字节长度前缀 + wire body）。
 * 共享单个 NetClient（Vert.x 推荐复用，避免每次连接创建实例导致并发资源耗尽）。
 */
public class ImClient {

  private static final byte JSON_CODEC_ID = 1;

  private final NetSocket socket;
  private final Map<String, CompletableFuture<ImMessage>> pending = new ConcurrentHashMap<>();

  private ImClient(NetSocket socket) {
    this.socket = socket;
    setupParser(socket);
  }

  public static Future<ImClient> connect(Vertx vertx, String host, int port) {
    NetClient client = SharedNetClient.get(vertx);
    return client.connect(port, host).map(ImClient::new);
  }

  private void setupParser(NetSocket socket) {
    RecordParser parser = RecordParser.newFixed(4);
    int[] size = {-1};
    parser.setOutput(buff -> {
      if (size[0] == -1) {
        size[0] = buff.getInt(0);
        parser.fixedSizeMode(size[0]);
      } else {
        ImMessage msg = new ImMessage();
        msg.readFromWire(buff);
        parser.fixedSizeMode(4);
        size[0] = -1;
        onMessage(msg);
      }
    });
    socket.handler(parser);
  }

  private void onMessage(ImMessage msg) {
    String messageId = msg.getMessageId();
    // 推送消息（C2C_NOTIFY / FRIEND_NOTIFY 等）messageId 为空，无 pending 对应，忽略
    if (messageId == null || messageId.isEmpty()) {
      return;
    }
    CompletableFuture<ImMessage> future = pending.remove(messageId);
    if (future != null) {
      future.complete(msg);
    }
  }

  Future<ImMessage> request(int cmd, JsonObject body, Map<String, String> headers) {
    String messageId = "bench-" + UUID.randomUUID();
    ImMessage msg = ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId(JSON_CODEC_ID)
      .cmd(cmd)
      .messageId(messageId)
      .body(body.toBuffer().getBytes())
      .varHeaders(headers)
      .build();
    CompletableFuture<ImMessage> future = new CompletableFuture<>();
    pending.put(messageId, future);
    socket.write(msg.encodeToWire());
    return Future.fromCompletionStage(future);
  }

  Future<ImMessage> login(String token, String userId, String userName) {
    JsonObject body = new JsonObject()
      .put("token", token)
      .put("userId", userId)
      .put("userName", userName)
      .put("deviceId", "bench")
      .put("platform", "bench")
      .put("appVersion", "1.0.0");
    return request(CMD_AUTH_REQ_VALUE, body, Map.of("userId", userId));
  }

  Future<ImMessage> addFriend(String selfId, String friendId) {
    JsonObject body = new JsonObject().put("userId", selfId).put("friendId", friendId);
    return request(CMD_FRIEND_ADD_REQ_VALUE, body, Map.of("userId", selfId));
  }

  Future<ImMessage> acceptFriend(String selfId, String friendId) {
    JsonObject body = new JsonObject().put("userId", selfId).put("friendId", friendId);
    return request(CMD_FRIEND_ACCEPT_REQ_VALUE, body, Map.of("userId", selfId));
  }

  Future<ImMessage> sendMessage(String selfId, String selfName, String peerId, String content) {
    JsonObject message = new JsonObject().put("msgType", 1).put("content", content);
    JsonObject body = new JsonObject()
      .put("senderId", selfId)
      .put("recipientId", peerId)
      .put("message", message);
    Map<String, String> headers = Map.of(
      "userId", selfId,
      "userName", selfName,
      "nickname", selfName);
    return request(CMD_C2C_REQ_VALUE, body, headers);
  }

  void close() {
    socket.close();
  }

  /** 共享 NetClient：Vert.x 推荐一个实例管理多个连接，避免并发连接时每次创建耗尽资源 */
  private static final class SharedNetClient {
    private static volatile NetClient instance;

    static NetClient get(Vertx vertx) {
      if (instance == null) {
        synchronized (SharedNetClient.class) {
          if (instance == null) {
            instance = vertx.createNetClient();
          }
        }
      }
      return instance;
    }
  }
}
