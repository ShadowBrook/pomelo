package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.config.ConfigHolder;
import com.github.moxib.pomelo.logic.infrastructure.PgPoolFactory;
import com.github.moxib.pomelo.logic.model.requests.FriendOpRequest;
import com.github.moxib.pomelo.logic.model.requests.SearchRequest;
import com.github.moxib.pomelo.model.PushEnvelope;
import com.github.moxib.pomelo.proto.relation.RelationProto;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.*;

public class FriendService extends ServiceBase {

  private static final Logger LOG = LoggerFactory.getLogger(FriendService.class);

  private static final String SEARCH_SQL = """
    SELECT id, user_name, nickname, avatar FROM im_user
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
  private static final String PROFILE_SQL = """
    SELECT user_name, nickname, avatar FROM im_user WHERE id = $1
    """;

  private final PushRouter pushRouter;
  private final Pool pgPool;
  private final MediaUrlSigner mediaUrlSigner;
  private final int searchLimit;

  public FriendService(Vertx vertx, PushRouter pushRouter, MediaUrlSigner mediaUrlSigner) {
    this(PgPoolFactory.get(vertx), pushRouter, ConfigHolder.getInt("friend.searchLimit", 20), mediaUrlSigner);
  }

  /** 供测试注入连接池 */
  FriendService(Pool pgPool, PushRouter pushRouter, int searchLimit, MediaUrlSigner mediaUrlSigner) {
    this.pushRouter = pushRouter;
    this.pgPool = pgPool;
    this.mediaUrlSigner = mediaUrlSigner;
    this.searchLimit = searchLimit;
  }

  public Future<ImMessage> process(ImMessage message) {
    int cmd = message.getCmd();
    try {
      // 身份只信 gateway 规范化后的 varHeader；缺失即未认证，不允许进入任何好友操作
      String userId = getUserIdFromHeaders(message);
      if (userId == null || userId.isBlank()) {
        return Future.succeededFuture(buildErrorResp(message, friendRespCmdFor(cmd),
          ErrorCode.UNAUTHORIZED, "未认证用户"));
      }
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
    SearchRequest req = decode(message, SearchRequest.class);
    String keyword = req.keyword();

    if (keyword == null || keyword.isBlank()) {
      return Future.succeededFuture(buildErrorResp(message, CMD_FRIEND_SEARCH_RESP_VALUE, ErrorCode.BAD_REQUEST, "keyword 不能为空"));
    }

    return pgPool.preparedQuery(SEARCH_SQL)
      .execute(Tuple.of("%" + keyword + "%", searchLimit))
      .map(rows -> buildResponse(message, CMD_FRIEND_SEARCH_RESP_VALUE, buildSearchBody(rows)));
  }

  private Object buildSearchBody(Iterable<Row> rows) {
    RelationProto.SearchUserResp.Builder b = RelationProto.SearchUserResp.newBuilder().setCode(0).setMessage("ok");
    for (Row row : rows) {
      b.addUsers(RelationProto.SearchUserResp.UserInfo.newBuilder()
        .setUserId(row.getLong("id")).setUserName(row.getString("user_name"))
        .setNickname(row.getString("nickname"))
        .setAvatar(mediaUrlSigner.signAvatar(row.getString("avatar"))));
    }
    return b.build();
  }

  private Future<ImMessage> handleAdd(ImMessage message) {
    Parsed p = parseFriendReq(message);
    if (p.invalid()) return Future.succeededFuture(buildErrorResp(message, CMD_FRIEND_ADD_RESP_VALUE, ErrorCode.BAD_REQUEST, "参数无效"));
    ResolvedIds ids = resolveBoth(p);
    long now = System.currentTimeMillis();
    return pgPool.preparedQuery(INSERT_FRIEND_SQL).execute(Tuple.of(ids.userId, ids.friendId, now))
      .compose(r -> {
        if (r.rowCount() == 0) return Future.failedFuture("已申请或已是好友");
        LOG.info("好友申请: userId={} friendId={}", ids.userId, ids.friendId);
        publishFriendNotify(ids.friendId, CMD_FRIEND_ADD_NOTIFY_VALUE, ids.userId);
        return Future.succeededFuture(buildFriendResp(message, CMD_FRIEND_ADD_RESP_VALUE, "申请已发送"));
      }).recover(e -> Future.succeededFuture(buildErrorResp(message, CMD_FRIEND_ADD_RESP_VALUE, ErrorCode.CONFLICT, e.getMessage())));
  }

  private Future<ImMessage> handleAccept(ImMessage message) {
    Parsed p = parseFriendReq(message);
    if (p.invalid()) return Future.succeededFuture(buildErrorResp(message, CMD_FRIEND_ACCEPT_RESP_VALUE, ErrorCode.BAD_REQUEST, "参数无效"));
    ResolvedIds ids = resolveBoth(p);
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
  }

  private Future<ImMessage> handleDelete(ImMessage message) {
    Parsed p = parseFriendReq(message);
    if (p.invalid()) return Future.succeededFuture(buildErrorResp(message, CMD_FRIEND_DELETE_RESP_VALUE, ErrorCode.BAD_REQUEST, "参数无效"));
    ResolvedIds ids = resolveBoth(p);
    return pgPool.preparedQuery(DELETE_FRIEND_SQL).execute(Tuple.of(ids.userId, ids.friendId))
      .onSuccess(r -> {
        LOG.info("好友删除: userId={} friendId={}", ids.userId, ids.friendId);
        publishFriendNotify(ids.friendId, CMD_FRIEND_DELETE_NOTIFY_VALUE, ids.userId);
      })
      .map(r -> buildFriendResp(message, CMD_FRIEND_DELETE_RESP_VALUE, "已删除"));
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

  /**
   * 解析好友操作参数。操作者（userId）只取 gateway 规范化后的 varHeader，
   * body 中的 userId 由客户端自由填写、可用于冒充他人（伪造申请/删除他人好友关系），一律忽略；
   * friendId 是操作对象，仍由 body 提供。
   */
  private Parsed parseFriendReq(ImMessage message) {
    FriendOpRequest req = decode(message, FriendOpRequest.class);
    return new Parsed(getUserIdFromHeaders(message), req.friendId());
  }

  private ResolvedIds resolveBoth(Parsed p) {
    return new ResolvedIds(Long.parseLong(p.userId), Long.parseLong(p.friendId));
  }

  private ImMessage buildFriendResp(ImMessage req, int respCmd, String msg) {
    Object body = switch (respCmd) {
      case CMD_FRIEND_ADD_RESP_VALUE -> RelationProto.FriendAddResp.newBuilder().setCode(0).setMessage(msg).build();
      case CMD_FRIEND_ACCEPT_RESP_VALUE -> RelationProto.FriendAcceptResp.newBuilder().setCode(0).setMessage(msg).build();
      default -> RelationProto.FriendDeleteResp.newBuilder().setCode(0).setMessage(msg).build();
    };
    return buildResponse(req, respCmd, body);
  }

  private void publishFriendNotify(long targetUserId, int cmd, long fromUserId) {
    loadUserProfile(fromUserId).onSuccess(profile -> {
      byte[] body = buildFriendNotifyBody(cmd, fromUserId, profile);
      PushEnvelope env = new PushEnvelope(String.valueOf(targetUserId), cmd, body);
      pushRouter.push(env);
      LOG.debug("FriendNotify 已提交推送: target={} cmd={}", targetUserId, cmd);
    }).onFailure(e -> LOG.warn("FriendNotify 查询用户信息失败 from={}: {}", fromUserId, e.getMessage()));
  }

  private Future<UserProfile> loadUserProfile(long userId) {
    return pgPool.preparedQuery(PROFILE_SQL)
      .execute(Tuple.of(userId))
      .map(rows -> {
        if (rows.size() == 0) {
          return new UserProfile("", "", "");
        }
        Row row = rows.iterator().next();
        return new UserProfile(nn(row.getString("user_name")), nn(row.getString("nickname")),
          mediaUrlSigner.signAvatar(row.getString("avatar")));
      });
  }

  private record UserProfile(String userName, String nickname, String avatar) {}

  private static String nn(String s) { return s != null ? s : ""; }

  private byte[] buildFriendNotifyBody(int cmd, long userId, UserProfile p) {
    if (cmd == CMD_FRIEND_ADD_NOTIFY_VALUE) {
      return RelationProto.FriendAddNotify.newBuilder()
        .setUserId(userId).setUserName(p.userName()).setNickname(p.nickname()).setAvatar(p.avatar())
        .build().toByteArray();
    }
    if (cmd == CMD_FRIEND_ACCEPT_NOTIFY_VALUE) {
      return RelationProto.FriendAcceptNotify.newBuilder()
        .setUserId(userId).setUserName(p.userName()).setNickname(p.nickname()).setAvatar(p.avatar())
        .build().toByteArray();
    }
    return RelationProto.FriendDeleteNotify.newBuilder().setUserId(userId).build().toByteArray();
  }
}
