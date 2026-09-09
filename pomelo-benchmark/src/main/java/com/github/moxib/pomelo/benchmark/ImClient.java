package com.github.moxib.pomelo.benchmark;

import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.proto.auth.AuthProto;
import com.github.moxib.pomelo.proto.chat.ChatProto;
import com.github.moxib.pomelo.proto.common.CommonProto;
import com.github.moxib.pomelo.proto.relation.RelationProto;
import com.google.protobuf.ByteString;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
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
 * 请求 body 一律为 Protobuf（codecId 冻结为 0）。
 */
public class ImClient {

  private final NetSocket socket;
  private final Map<String, CompletableFuture<ImMessage>> pending = new ConcurrentHashMap<>();

  private ImClient(NetSocket socket) {
    this.socket = socket;
    setupParser(socket);
  }

  public static Future<ImClient> connect(Vertx vertx, String host, int port) {
    return connect(vertx, host, port, false);
  }

  /** tls=true 时以 TLS 握手连接网关（服务端 tls.enabled=true 时必须开启） */
  public static Future<ImClient> connect(Vertx vertx, String host, int port, boolean tls) {
    NetClient client = SharedNetClient.get(vertx, tls);
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

  Future<ImMessage> request(int cmd, com.google.protobuf.Message body, Map<String, String> headers) {
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
    socket.write(msg.encodeToWire());
    return Future.fromCompletionStage(future);
  }

  Future<ImMessage> login(String token, String userId, String userName) {
    AuthProto.AuthReq body = AuthProto.AuthReq.newBuilder()
      .setToken(token)
      .setDeviceId("bench")
      .setPlatform("bench")
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
    socket.close();
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
              // 压测场景接受自签名证书
              tls = vertx.createNetClient(new NetClientOptions().setSsl(true).setTrustAll(true));
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
