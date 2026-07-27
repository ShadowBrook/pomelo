package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.codec.CodecRegistry;
import com.github.moxib.pomelo.codec.ProtobufCodec;
import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.config.ConfigHolder;
import com.github.moxib.pomelo.logic.infrastructure.MessageRepository;
import com.github.moxib.pomelo.logic.infrastructure.PgPoolFactory;
import com.github.moxib.pomelo.logic.model.requests.FriendOpRequest;
import com.github.moxib.pomelo.logic.model.requests.SearchRequest;
import com.github.moxib.pomelo.model.PushEnvelope;
import com.github.moxib.pomelo.proto.relation.RelationProto;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.*;

public class FriendService extends ServiceBase {

  private static final Logger LOG = LoggerFactory.getLogger(FriendService.class);

  private static final String SEARCH_SQL = """
    SELECT user_id, user_name, nickname, avatar FROM im_user
    WHERE user_name LIKE $1 OR nickname LIKE $1 LIMIT $2
    """;
  private static final String INSERT_FRIEND_SQL = """
    INSERT INTO im_friend (user_id, friend_id, status, created_at)
    VALUES ($1, $2, 0, $3) ON CONFLICT (user_id, friend_id) DO NOTHING
    """;
  private static final String ACCEPT_FRIEND_SQL = """
    UPDATE im_friend SET status = 1 WHERE user_id = $1 AND friend_id = $2 AND status = 0
    """;
  private static final String INSERT_REVERSE_SQL = """
    INSERT INTO im_friend (user_id, friend_id, status, created_at)
    VALUES ($1, $2, 1, $3) ON CONFLICT (user_id, friend_id) DO NOTHING
    """;
  private static final String DELETE_FRIEND_SQL = """
    DELETE FROM im_friend WHERE (user_id = $1 AND friend_id = $2) OR (user_id = $2 AND friend_id = $1)
    """;

  private final Vertx vertx;
  private final MessageRepository messageRepo;
  private final CodecRegistry codecRegistry;
  private final Pool pgPool;
  private final int searchLimit;

  public FriendService(Vertx vertx, MessageRepository messageRepo) {
    this.vertx = vertx;
    this.messageRepo = messageRepo;
    this.pgPool = PgPoolFactory.get(vertx);
    this.searchLimit = ConfigHolder.getInt("friend.searchLimit", 20);
    this.codecRegistry = new CodecRegistry();
    codecRegistry.registerProtobuf(CMD_FRIEND_SEARCH_REQ_VALUE, RelationProto.SearchUserReq.parser(), SearchRequest::fromProto, SearchRequest.class);
    codecRegistry.registerJson(CMD_FRIEND_SEARCH_REQ_VALUE, SearchRequest.class);
    codecRegistry.registerProtobuf(CMD_FRIEND_ADD_REQ_VALUE, RelationProto.FriendAddReq.parser(), FriendOpRequest::fromAddProto, FriendOpRequest.class);
    codecRegistry.registerJson(CMD_FRIEND_ADD_REQ_VALUE, FriendOpRequest.class);
    codecRegistry.registerProtobuf(CMD_FRIEND_ACCEPT_REQ_VALUE, RelationProto.FriendAcceptReq.parser(), FriendOpRequest::fromAcceptProto, FriendOpRequest.class);
    codecRegistry.registerJson(CMD_FRIEND_ACCEPT_REQ_VALUE, FriendOpRequest.class);
    codecRegistry.registerProtobuf(CMD_FRIEND_DELETE_REQ_VALUE, RelationProto.FriendDeleteReq.parser(), FriendOpRequest::fromDeleteProto, FriendOpRequest.class);
    codecRegistry.registerJson(CMD_FRIEND_DELETE_REQ_VALUE, FriendOpRequest.class);
  }

  public Future<ImMessage> process(ImMessage message) {
    int cmd = message.getCmd();
    try {
      if (cmd == CMD_FRIEND_SEARCH_REQ_VALUE)       return handleSearch(message);
      else if (cmd == CMD_FRIEND_ADD_REQ_VALUE)     return handleAdd(message);
      else if (cmd == CMD_FRIEND_ACCEPT_REQ_VALUE)  return handleAccept(message);
      else if (cmd == CMD_FRIEND_DELETE_REQ_VALUE)  return handleDelete(message);
      return Future.succeededFuture(buildErrorResp(message, friendRespCmdFor(cmd), ErrorCode.BAD_REQUEST, "未知好友操作"));
    } catch (Exception e) {
      LOG.error("好友操作失败 cmd={}", cmd, e);
      return Future.succeededFuture(buildErrorResp(message, friendRespCmdFor(cmd), ErrorCode.INTERNAL_ERROR, "操作失败: " + e.getMessage()));
    }
  }

  private Future<ImMessage> handleSearch(ImMessage message) {
    SearchRequest req = decode(codecRegistry, message, SearchRequest.class);
    String keyword = req.keyword();
    byte codecId = message.getCodecId();

    if (keyword == null || keyword.isBlank()) {
      return Future.succeededFuture(buildErrorResp(message, CMD_FRIEND_SEARCH_RESP_VALUE, ErrorCode.BAD_REQUEST, "keyword 不能为空"));
    }

    return pgPool.preparedQuery(SEARCH_SQL)
      .execute(Tuple.of("%" + keyword + "%", searchLimit))
      .map(rows -> buildResponse(message, CMD_FRIEND_SEARCH_RESP_VALUE, buildSearchBody(codecId, rows)));
  }

  private Object buildSearchBody(byte codecId, Iterable<Row> rows) {
    if (codecId == ProtobufCodec.CODEC_ID) {
      RelationProto.SearchUserResp.Builder b = RelationProto.SearchUserResp.newBuilder().setCode(0).setMessage("ok");
      for (Row row : rows) {
        b.addUsers(RelationProto.SearchUserResp.UserInfo.newBuilder()
          .setUserId(row.getString("user_id")).setUserName(row.getString("user_name"))
          .setNickname(row.getString("nickname")).setAvatar(row.getString("avatar")));
      }
      return b.build();
    }
    JsonObject json = jsonBody().put("code", 0).put("message", "ok");
    JsonArray arr = new JsonArray(); json.put("users", arr);
    for (Row row : rows) {
      arr.add(new JsonObject().put("userId", row.getString("user_id"))
        .put("userName", row.getString("user_name")).put("nickname", row.getString("nickname"))
        .put("avatar", row.getString("avatar")));
    }
    return json;
  }

  private Future<ImMessage> handleAdd(ImMessage message) {
    Parsed p = parseFriendReq(message);
    if (p.invalid()) return Future.succeededFuture(buildErrorResp(message, CMD_FRIEND_ADD_RESP_VALUE, ErrorCode.BAD_REQUEST, "参数无效"));
    return resolveBoth(p).compose(ids -> {
      long now = System.currentTimeMillis();
      return pgPool.preparedQuery(INSERT_FRIEND_SQL).execute(Tuple.of(ids.userId, ids.friendId, now))
        .compose(r -> {
          if (r.rowCount() == 0) return Future.failedFuture("已申请或已是好友");
          LOG.info("好友申请: userId={} friendId={}", ids.userId, ids.friendId);
          publishFriendNotify(ids.friendId, CMD_FRIEND_ADD_NOTIFY_VALUE, ids.userId);
          return Future.succeededFuture(buildFriendResp(message, CMD_FRIEND_ADD_RESP_VALUE, "申请已发送"));
        });
    }).recover(e -> Future.succeededFuture(buildErrorResp(message, CMD_FRIEND_ADD_RESP_VALUE, ErrorCode.CONFLICT, e.getMessage())));
  }

  private Future<ImMessage> handleAccept(ImMessage message) {
    Parsed p = parseFriendReq(message);
    if (p.invalid()) return Future.succeededFuture(buildErrorResp(message, CMD_FRIEND_ACCEPT_RESP_VALUE, ErrorCode.BAD_REQUEST, "参数无效"));
    return resolveBoth(p).compose(ids -> {
      long now = System.currentTimeMillis();
      return pgPool.preparedQuery(ACCEPT_FRIEND_SQL).execute(Tuple.of(ids.friendId, ids.userId))
        .compose(r -> r.rowCount() == 0
          ? Future.failedFuture("没有待处理的申请")
          : pgPool.preparedQuery(INSERT_REVERSE_SQL).execute(Tuple.of(ids.userId, ids.friendId, now)))
        .onSuccess(r -> {
          LOG.info("好友接受: userId={} friendId={}", ids.userId, ids.friendId);
          publishFriendNotify(ids.friendId, CMD_FRIEND_ACCEPT_NOTIFY_VALUE, ids.userId);
        })
        .map(r -> buildFriendResp(message, CMD_FRIEND_ACCEPT_RESP_VALUE, "已添加好友"));
    }).recover(e -> Future.succeededFuture(buildErrorResp(message, CMD_FRIEND_ACCEPT_RESP_VALUE, ErrorCode.BAD_REQUEST, e.getMessage())));
  }

  private Future<ImMessage> handleDelete(ImMessage message) {
    Parsed p = parseFriendReq(message);
    if (p.invalid()) return Future.succeededFuture(buildErrorResp(message, CMD_FRIEND_DELETE_RESP_VALUE, ErrorCode.BAD_REQUEST, "参数无效"));
    return resolveBoth(p).compose(ids ->
      pgPool.preparedQuery(DELETE_FRIEND_SQL).execute(Tuple.of(ids.userId, ids.friendId))
        .onSuccess(r -> {
          LOG.info("好友删除: userId={} friendId={}", ids.userId, ids.friendId);
          publishFriendNotify(ids.friendId, CMD_FRIEND_DELETE_NOTIFY_VALUE, ids.userId);
        })
        .map(r -> buildFriendResp(message, CMD_FRIEND_DELETE_RESP_VALUE, "已删除"))
    ).recover(e -> Future.succeededFuture(buildErrorResp(message, CMD_FRIEND_DELETE_RESP_VALUE, ErrorCode.INTERNAL_ERROR, "删除失败")));
  }

  // -- helpers --

  private int friendRespCmdFor(int reqCmd) {
    return switch (reqCmd) {
      case CMD_FRIEND_SEARCH_REQ_VALUE -> CMD_FRIEND_SEARCH_RESP_VALUE;
      case CMD_FRIEND_ADD_REQ_VALUE -> CMD_FRIEND_ADD_RESP_VALUE;
      case CMD_FRIEND_ACCEPT_REQ_VALUE -> CMD_FRIEND_ACCEPT_RESP_VALUE;
      case CMD_FRIEND_DELETE_REQ_VALUE -> CMD_FRIEND_DELETE_RESP_VALUE;
      default -> CMD_ERROR_VALUE;
    };
  }

  private record Parsed(String userId, String friendId) {
    boolean invalid() { return userId == null || userId.isBlank() || friendId == null || friendId.isBlank() || userId.equals(friendId); }
  }

  private record ResolvedIds(long userId, long friendId) {}

  private Parsed parseFriendReq(ImMessage message) {
    FriendOpRequest req = decode(codecRegistry, message, FriendOpRequest.class);
    return new Parsed(req.userId(), req.friendId());
  }

  private Future<ResolvedIds> resolveBoth(Parsed p) {
    return resolveId(p.userId).compose(uid -> resolveId(p.friendId).map(fid -> new ResolvedIds(uid, fid)));
  }

  private Future<Long> resolveId(String userId) {
    try { return Future.succeededFuture(Long.parseLong(userId)); }
    catch (NumberFormatException e) { return messageRepo.findUserId(userId).map(foundId -> foundId != 0 ? foundId : 0L); }
  }

  private ImMessage buildFriendResp(ImMessage req, int respCmd, String msg) {
    byte codecId = req.getCodecId();
    Object body;
    if (codecId == ProtobufCodec.CODEC_ID) {
      body = switch (respCmd) {
        case CMD_FRIEND_ADD_RESP_VALUE -> RelationProto.FriendAddResp.newBuilder().setCode(0).setMessage(msg).build();
        case CMD_FRIEND_ACCEPT_RESP_VALUE -> RelationProto.FriendAcceptResp.newBuilder().setCode(0).setMessage(msg).build();
        default -> RelationProto.FriendDeleteResp.newBuilder().setCode(0).setMessage(msg).build();
      };
    } else {
      body = jsonBody().put("code", 0).put("message", msg);
    }
    return buildResponse(req, respCmd, body);
  }

  private void publishFriendNotify(long targetUserId, int cmd, long fromUserId) {
    // 使用数字 id 作为 targetUserId（Gateway 侧会查 SessionRegistry 或忽略）
    PushEnvelope env = new PushEnvelope(
      String.valueOf(targetUserId),
      cmd,
      buildFriendNotifyBody(cmd, String.valueOf(fromUserId)),
      (byte) 0
    );
    vertx.eventBus().publish("gateway.push", JsonObject.mapFrom(env));
    LOG.debug("FriendNotify 已广播: target={} cmd={}", targetUserId, cmd);
  }

  private byte[] buildFriendNotifyBody(int cmd, String userId) {
    if (cmd == CMD_FRIEND_ADD_NOTIFY_VALUE)
      return RelationProto.FriendAddNotify.newBuilder()
        .setUserId(userId).setUserName("").setNickname("").setAvatar("").build().toByteArray();
    if (cmd == CMD_FRIEND_ACCEPT_NOTIFY_VALUE)
      return RelationProto.FriendAcceptNotify.newBuilder()
        .setUserId(userId).setUserName("").setNickname("").setAvatar("").build().toByteArray();
    return RelationProto.FriendDeleteNotify.newBuilder().setUserId(userId).build().toByteArray();
  }
}
