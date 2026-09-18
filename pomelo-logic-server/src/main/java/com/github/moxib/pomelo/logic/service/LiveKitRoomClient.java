package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.config.ConfigHolder;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LiveKit RoomService 轻客户端（Twirp JSON 模式）。
 * <p>
 * LiveKit 的管理 API 是 Twirp 协议，接受 application/json 与 protobuf 两种编码；
 * 这里只封装通话需要的两个调用（建房/删房），避免引入 livekit-server SDK 的
 * protobuf/jackson 版本冲突。
 */
public class LiveKitRoomClient implements CallRoomManager {

  private static final Logger LOG = LoggerFactory.getLogger(LiveKitRoomClient.class);

  private final WebClient client;
  private final LiveKitTokenService tokens;
  private final String host;

  public LiveKitRoomClient(Vertx vertx, LiveKitTokenService tokens) {
    this.tokens = tokens;
    this.host = ConfigHolder.getString("livekit.host", "http://livekit:7880");
    this.client = WebClient.create(vertx, new WebClientOptions());
  }

  /** 供测试注入 host */
  LiveKitRoomClient(WebClient client, LiveKitTokenService tokens, String host) {
    this.client = client;
    this.tokens = tokens;
    this.host = host;
  }

  @Override
  public Future<Void> createRoom(String room, int emptyTimeoutSeconds, int maxParticipants) {
    JsonObject body = new JsonObject()
      .put("name", room)
      .put("empty_timeout", emptyTimeoutSeconds)
      .put("max_participants", maxParticipants);
    return post("/twirp/livekit.RoomService/CreateRoom", body, "CreateRoom " + room);
  }

  @Override
  public Future<Void> deleteRoom(String room) {
    return post("/twirp/livekit.RoomService/DeleteRoom", new JsonObject().put("room", room), "DeleteRoom " + room)
      // 删房尽力而为：失败不阻塞收尾，empty_timeout 会兜底关闭
      .recover(err -> {
        LOG.warn("LiveKit 删房失败（由 empty_timeout 兜底）room={}: {}", room, err.getMessage());
        return Future.succeededFuture();
      });
  }

  private Future<Void> post(String path, JsonObject body, String what) {
    return client.postAbs(host + path)
      .putHeader("Authorization", "Bearer " + tokens.issueServiceToken())
      .putHeader("Content-Type", "application/json")
      .sendJsonObject(body)
      .map(resp -> {
        if (resp.statusCode() == 200) {
          return (Void) null;
        }
        throw new IllegalStateException(what + " 失败: HTTP " + resp.statusCode() + " " + resp.bodyAsString());
      });
  }
}
