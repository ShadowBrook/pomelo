package com.github.moxib.pomelo.gateway.handler;

import com.github.moxib.pomelo.codec.CodecRegistry;
import com.github.moxib.pomelo.codec.ProtobufCodec;
import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.proto.relation.RelationProto;
import com.github.moxib.pomelo.service.MessageRepository;
import com.github.moxib.pomelo.service.MessageService;
import com.github.moxib.pomelo.service.PgPoolFactory;
import com.github.moxib.pomelo.service.model.requests.FriendOpRequest;
import com.github.moxib.pomelo.service.model.requests.SearchRequest;
import com.github.moxib.pomelo.utils.IdGenerator;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.*;

/**
 * 好友关系处理器。
 * 协议层使用 userId (NanoID)，内部解析为 im_user.id (BIGINT) 后入库。
 */
public class FriendHandler extends AbstractMessageHandler {

  private static final Logger LOG = LoggerFactory.getLogger(FriendHandler.class);

  private static final String SEARCH_SQL = """
    SELECT user_id, user_name, nickname, avatar FROM im_user
    WHERE user_name LIKE $1 OR nickname LIKE $1 LIMIT 20
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
  private static final String FIND_USER_INFO_SQL = """
    SELECT user_name, nickname, avatar FROM im_user WHERE id = $1
    """;

  private final Pool pgPool;

  public FriendHandler(Vertx vertx, CodecRegistry codecRegistry,
                        SessionRegistry sessionRegistry, MessageRepository messageRepo,
                        MessageService messageService, IdGenerator idGenerator) {
    super(vertx, codecRegistry, sessionRegistry, messageRepo, messageService, idGenerator);
    this.pgPool = PgPoolFactory.get(vertx);
  }

  @Override
  public void handle(Connection connection, ImMessage message) {
    int cmd = message.getCmd();
    try {
      if (cmd == CMD_FRIEND_SEARCH_REQ_VALUE)       handleSearch(connection, message);
      else if (cmd == CMD_FRIEND_ADD_REQ_VALUE)     handleAdd(connection, message);
      else if (cmd == CMD_FRIEND_ACCEPT_REQ_VALUE)  handleAccept(connection, message);
      else if (cmd == CMD_FRIEND_DELETE_REQ_VALUE)  handleDelete(connection, message);
      else sendErrorResponse(connection, message, friendRespCmdFor(cmd), ErrorCode.BAD_REQUEST, "未知好友操作");
    } catch (Exception e) {
      LOG.error("好友操作失败 cmd={}", cmd, e);
      sendErrorResponse(connection, message, friendRespCmdFor(cmd), ErrorCode.INTERNAL_ERROR, "操作失败: " + e.getMessage());
    }
  }

  // ---- 搜索 ----
  private void handleSearch(Connection connection, ImMessage message) {
    SearchRequest req = decodeRequest(message, SearchRequest.class);
    String keyword = req.keyword();
    byte codecId = message.getCodecId();

    if (keyword == null || keyword.isBlank()) {
      sendErrorResponse(connection, message, CMD_FRIEND_SEARCH_RESP_VALUE, ErrorCode.BAD_REQUEST, "keyword 不能为空");
      return;
    }

    pgPool.preparedQuery(SEARCH_SQL)
      .execute(Tuple.of("%" + keyword + "%"))
      .onSuccess(rows -> sendResponse(connection,
        buildResponse(message, CMD_FRIEND_SEARCH_RESP_VALUE, buildSearchBody(codecId, rows))))
      .onFailure(e -> sendErrorResponse(connection, message, CMD_FRIEND_SEARCH_RESP_VALUE, ErrorCode.INTERNAL_ERROR, "搜索失败"));
  }

  private Object buildSearchBody(byte codecId, RowSet<Row> rows) {
    if (codecId == ProtobufCodec.CODEC_ID) {
      RelationProto.SearchUserResp.Builder b = RelationProto.SearchUserResp.newBuilder().setCode(0).setMessage("ok");
      for (Row row : rows) {
        b.addUsers(RelationProto.SearchUserResp.UserInfo.newBuilder()
          .setUserId(row.getString("user_id"))
          .setUserName(row.getString("user_name"))
          .setNickname(row.getString("nickname"))
          .setAvatar(row.getString("avatar")));
      }
      return b.build();
    }
    JsonObject json = jsonBody().put("code", 0).put("message", "ok");
    JsonArray arr = new JsonArray(); json.put("users", arr);
    for (Row row : rows) {
      arr.add(new JsonObject()
        .put("userId", row.getString("user_id"))
        .put("userName", row.getString("user_name"))
        .put("nickname", row.getString("nickname"))
        .put("avatar", row.getString("avatar")));
    }
    return json;
  }

  // ---- 添加 ----
  private void handleAdd(Connection connection, ImMessage message) {
    Parsed p = parseFriendReq(message);
    if (p.invalid()) { sendErrorResponse(connection, message, CMD_FRIEND_ADD_RESP_VALUE, ErrorCode.BAD_REQUEST, "参数无效"); return; }

    resolveBoth(p)
      .compose(ids -> {
        long now = System.currentTimeMillis();
        return pgPool.preparedQuery(INSERT_FRIEND_SQL).execute(Tuple.of(ids.userId, ids.friendId, now))
          .compose(r -> {
            if (r.rowCount() == 0) return Future.failedFuture("已申请或已是好友");
            LOG.info("好友申请: userId={} friendId={}", ids.userId, ids.friendId);
            sendResponse(connection, buildFriendResp(message, CMD_FRIEND_ADD_RESP_VALUE, "申请已发送"));
            pushNotify(ids.friendId, CMD_FRIEND_ADD_NOTIFY_VALUE, ids.userId);
            return Future.succeededFuture();
          });
      })
      .onFailure(e -> sendErrorResponse(connection, message, CMD_FRIEND_ADD_RESP_VALUE, ErrorCode.CONFLICT, e.getMessage()));
  }

  // ---- 接受 ----
  private void handleAccept(Connection connection, ImMessage message) {
    Parsed p = parseFriendReq(message);
    if (p.invalid()) { sendErrorResponse(connection, message, CMD_FRIEND_ACCEPT_RESP_VALUE, ErrorCode.BAD_REQUEST, "参数无效"); return; }

    resolveBoth(p)
      .compose(ids -> {
        long now = System.currentTimeMillis();
        return pgPool.preparedQuery(ACCEPT_FRIEND_SQL).execute(Tuple.of(ids.friendId, ids.userId))
          .compose(r -> r.rowCount() == 0
            ? Future.failedFuture("没有待处理的申请")
            : pgPool.preparedQuery(INSERT_REVERSE_SQL).execute(Tuple.of(ids.userId, ids.friendId, now)))
          .onSuccess(r -> {
            LOG.info("好友接受: userId={} friendId={}", ids.userId, ids.friendId);
            sendResponse(connection, buildFriendResp(message, CMD_FRIEND_ACCEPT_RESP_VALUE, "已添加好友"));
            pushNotify(ids.friendId, CMD_FRIEND_ACCEPT_NOTIFY_VALUE, ids.userId);
          });
      })
      .onFailure(e -> sendErrorResponse(connection, message, CMD_FRIEND_ACCEPT_RESP_VALUE, ErrorCode.BAD_REQUEST, e.getMessage()));
  }

  // ---- 删除 ----
  private void handleDelete(Connection connection, ImMessage message) {
    Parsed p = parseFriendReq(message);
    if (p.invalid()) { sendErrorResponse(connection, message, CMD_FRIEND_DELETE_RESP_VALUE, ErrorCode.BAD_REQUEST, "参数无效"); return; }

    resolveBoth(p)
      .compose(ids ->
        pgPool.preparedQuery(DELETE_FRIEND_SQL).execute(Tuple.of(ids.userId, ids.friendId))
          .onSuccess(r -> {
            LOG.info("好友删除: userId={} friendId={}", ids.userId, ids.friendId);
            sendResponse(connection, buildFriendResp(message, CMD_FRIEND_DELETE_RESP_VALUE, "已删除"));
            pushNotify(ids.friendId, CMD_FRIEND_DELETE_NOTIFY_VALUE, ids.userId);
          }))
      .onFailure(e -> sendErrorResponse(connection, message, CMD_FRIEND_DELETE_RESP_VALUE, ErrorCode.INTERNAL_ERROR, "删除失败"));
  }

  // ---- helpers ----

  /** 好友请求 cmd → 对应响应 cmd */
  private int friendRespCmdFor(int reqCmd) {
    return switch (reqCmd) {
      case CMD_FRIEND_SEARCH_REQ_VALUE -> CMD_FRIEND_SEARCH_RESP_VALUE;
      case CMD_FRIEND_ADD_REQ_VALUE -> CMD_FRIEND_ADD_RESP_VALUE;
      case CMD_FRIEND_ACCEPT_REQ_VALUE -> CMD_FRIEND_ACCEPT_RESP_VALUE;
      case CMD_FRIEND_DELETE_REQ_VALUE -> CMD_FRIEND_DELETE_RESP_VALUE;
      default -> CMD_ERROR_VALUE;
    };
  }

  /** 协议层身份（NanoID userId） */
  private record Parsed(String userId, String friendId) {
    boolean invalid() { return userId == null || userId.isBlank() || friendId == null || friendId.isBlank() || userId.equals(friendId); }
  }

  /** 解析后的数字 id */
  private record ResolvedIds(long userId, long friendId) {}

  private Parsed parseFriendReq(ImMessage message) {
    FriendOpRequest req = decodeRequest(message, FriendOpRequest.class);
    return new Parsed(req.userId(), req.friendId());
  }

  /** 将两个 userId (NanoID) 解析为 numeric id */
  private Future<ResolvedIds> resolveBoth(Parsed p) {
    return resolveId(p.userId)
      .compose(uid ->
        resolveId(p.friendId)
          .map(fid -> new ResolvedIds(uid, fid)));
  }

  /** userId→id 解析：先查 SessionRegistry（在线），再查 DB */
  private Future<Long> resolveId(String userId) {
    long id = sessionRegistry.getId(userId);
    if (id != 0) return Future.succeededFuture(id);
    return messageRepo.findUserId(userId)
      .map(foundId -> foundId != 0 ? foundId : 0L);
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

  private void pushNotify(long targetUserId, int cmd, long fromUserId) {
    Connection conn = sessionRegistry.getConnection(targetUserId);
    if (conn == null) return;

    // 获取通知对象用户名的 userId (NanoID)
    String fromUserIdStr = sessionRegistry.getUserId(fromUserId);

    pgPool.preparedQuery(FIND_USER_INFO_SQL).execute(Tuple.of(fromUserId)).onSuccess(rows -> {
      if (rows.size() == 0) return;
      Row r = rows.iterator().next();

      byte targetCodec = sessionRegistry.getCodec(targetUserId);
      String targetUserIdStr = sessionRegistry.getUserId(targetUserId);
      Object body = buildNotifyBody(targetCodec, cmd,
        fromUserIdStr != null ? fromUserIdStr : String.valueOf(fromUserId),
        r.getString("user_name"), r.getString("nickname"), r.getString("avatar"));
      ImMessage push = buildPushMessage(targetUserIdStr != null ? targetUserIdStr : String.valueOf(targetUserId),
        cmd, String.valueOf(System.currentTimeMillis()), body);
      conn.write(push.encodeToWire());
    });
  }

  private Object buildNotifyBody(byte codecId, int cmd, String userId, String userName, String nickname, String avatar) {
    if (codecId == ProtobufCodec.CODEC_ID) {
      if (cmd == CMD_FRIEND_ADD_NOTIFY_VALUE)
        return RelationProto.FriendAddNotify.newBuilder()
          .setUserId(userId).setUserName(userName).setNickname(nickname).setAvatar(avatar).build();
      if (cmd == CMD_FRIEND_ACCEPT_NOTIFY_VALUE)
        return RelationProto.FriendAcceptNotify.newBuilder()
          .setUserId(userId).setUserName(userName).setNickname(nickname).setAvatar(avatar).build();
      return RelationProto.FriendDeleteNotify.newBuilder().setUserId(userId).build();
    }
    JsonObject json = jsonBody().put("userId", userId);
    if (cmd != CMD_FRIEND_DELETE_NOTIFY_VALUE) json.put("userName", userName).put("nickname", nickname).put("avatar", avatar);
    return json;
  }
}
