package com.github.moxib.pomelo.logic;

import at.favre.lib.crypto.bcrypt.BCrypt;
import com.github.moxib.pomelo.config.ConfigHolder;
import com.github.moxib.pomelo.config.SessionRouteTable;
import com.github.moxib.pomelo.logic.infrastructure.PgPoolFactory;
import com.github.moxib.pomelo.logic.infrastructure.RedisFactory;
import com.github.moxib.pomelo.logic.infrastructure.TokenService;
import com.github.moxib.pomelo.logic.id.SnowflakeIdGenerator;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * HTTP API Verticle — 短连接业务接口。
 * 注册、登录、资料查询、好友列表等场景。
 * 端口：{@code api.http.port}（默认 8080）。
 */
public class ApiVerticle extends VerticleBase {

  private static final Logger LOG = LoggerFactory.getLogger(ApiVerticle.class);

  private static final String INSERT_USER_SQL = """
    INSERT INTO im_user (id, user_name, nickname, avatar, password, status, created_at, updated_at)
    VALUES ($1, $2, $3, $4, $5, 0, $6, $6) ON CONFLICT (user_name) DO NOTHING
    """;
  private static final String FIND_USER_SQL = """
    SELECT id, user_name, nickname, avatar, status, created_at FROM im_user WHERE id = $1
    """;
  private static final String FIND_BY_USERNAME_SQL = """
    SELECT id, user_name, nickname, avatar, password, status, created_at
    FROM im_user WHERE user_name = $1
    """;
  private static final String LIST_FRIENDS_SQL = """
    SELECT u.id, u.user_name, u.nickname, u.avatar, u.status, f.created_at AS friended_at
    FROM im_friend f
    JOIN im_user me ON f.user_id = me.id
    JOIN im_user u  ON f.friend_id = u.id
    WHERE me.id = $1 AND f.status = 1 ORDER BY f.created_at DESC
    """;
  private static final String LIST_PENDING_SQL = """
    SELECT u.id, u.user_name, u.nickname, u.avatar, u.status, f.created_at AS requested_at
    FROM im_friend f
    JOIN im_user me ON f.friend_id = me.id
    JOIN im_user u  ON f.user_id = u.id
    WHERE me.id = $1 AND f.status = 0 ORDER BY f.created_at DESC
    """;

  private int port;
  private int bcryptCost;
  private SnowflakeIdGenerator snowflake;

  private HttpServer server;
  private Pool pgPool;
  private SessionRouteTable routeTable;

  @Override
  public Future<?> start() {
    this.port = ConfigHolder.getInt("api.http.port", 8888);
    this.bcryptCost = ConfigHolder.getInt("bcrypt.cost", 12);
    this.snowflake = new SnowflakeIdGenerator(
      ConfigHolder.getInt("snowflake.workerId", 1));
    pgPool = PgPoolFactory.get(vertx);
    routeTable = new SessionRouteTable(vertx);

    return RedisFactory.get(vertx).connect()
      .compose(v -> {
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        router.get("/api/health").handler(this::health);
        router.post("/api/user/register").handler(this::register);
        router.post("/api/user/login").handler(this::login);
        router.get("/api/user/:userId/profile").handler(this::profile);
        router.get("/api/friends/:userId").handler(this::friends);
        router.get("/api/friends/:userId/pending").handler(this::pending);

        server = vertx.createHttpServer();
        return server.requestHandler(router).listen(port)
          .onSuccess(v2 -> LOG.info("ApiVerticle 已启动，端口: {}", port))
          .onFailure(e -> LOG.error("ApiVerticle 启动失败", e));
      });
  }

  @Override
  public Future<?> stop() {
    return server != null ? server.close() : Future.succeededFuture();
  }

  private void health(RoutingContext ctx) {
    ctx.json(new JsonObject().put("status", "ok"));
  }

  /** POST /api/user/register — userName + password，返回 Snowflake userId */
  private void register(RoutingContext ctx) {
    JsonObject body = ctx.body().asJsonObject();
    String userName = body.getString("userName");
    String nickname = body.getString("nickname", userName);
    String password = body.getString("password");
    String avatar = body.getString("avatar", "");

    if (isBlank(userName) || isBlank(password)) { fail(ctx, 400, "userName 和 password 不能为空"); return; }

    long id = snowflake.nextId();
    String userId = String.valueOf(id);
    String hash = BCrypt.withDefaults().hashToString(bcryptCost, password.toCharArray());
    long now = System.currentTimeMillis();

    pgPool.preparedQuery(INSERT_USER_SQL).execute(Tuple.of(id, userName, nickname, avatar, hash, now))
      .onSuccess(r -> {
        if (r.rowCount() > 0) {
          String token = TokenService.get(vertx).generate(userId, userName, nickname, "");
          LOG.info("注册成功: userId={} userName={}", userId, userName);
          ok(ctx, 201, new JsonObject().put("userId", userId).put("userName", userName).put("token", token));
        } else {
          fail(ctx, 409, "用户名已存在");
        }
      })
      .onFailure(e -> fail(ctx, 500, "注册失败"));
  }

  /** POST /api/user/login — userName + password，返回 userId（Snowflake 字符串） */
  private void login(RoutingContext ctx) {
    JsonObject body = ctx.body().asJsonObject();
    String userName = body.getString("userName");
    String password = body.getString("password");

    if (isBlank(userName) || isBlank(password)) { fail(ctx, 400, "userName 和 password 不能为空"); return; }

    pgPool.preparedQuery(FIND_BY_USERNAME_SQL).execute(Tuple.of(userName))
      .onSuccess(rows -> {
        if (rows.size() == 0) { fail(ctx, 401, "用户名不存在"); return; }
        Row r = rows.iterator().next();
        String hash = r.getString("password");
        if (!BCrypt.verifyer().verify(password.toCharArray(), hash).verified) {
          fail(ctx, 401, "密码错误");
          return;
        }
        long id = r.getLong("id");
        String userId = String.valueOf(id);
        String platform = body.getString("platform", "");
        String token = TokenService.get(vertx).generate(userId,
          r.getString("user_name"), r.getString("nickname"), platform);
        ok(ctx, 200, new JsonObject()
          .put("userId", userId)
          .put("userName", r.getString("user_name"))
          .put("nickname", r.getString("nickname"))
          .put("avatar", r.getString("avatar"))
          .put("token", token));
      })
      .onFailure(e -> fail(ctx, 500, "登录失败"));
  }

  /** GET /api/user/:userId/profile — 按 Snowflake ID 查询 */
  private void profile(RoutingContext ctx) {
    String userId = ctx.pathParam("userId");
    long id;
    try {
      id = Long.parseLong(userId);
    } catch (NumberFormatException e) {
      fail(ctx, 400, "无效的 userId");
      return;
    }
    pgPool.preparedQuery(FIND_USER_SQL).execute(Tuple.of(id))
      .onSuccess(rows -> {
        if (rows.size() == 0) { fail(ctx, 404, "用户不存在"); return; }
        Row r = rows.iterator().next();
        ok(ctx, 200, new JsonObject()
          .put("userId", String.valueOf(r.getLong("id")))
          .put("userName", r.getString("user_name"))
          .put("nickname", r.getString("nickname"))
          .put("avatar", r.getString("avatar"))
          .put("status", r.getInteger("status"))
          .put("createdAt", r.getLong("created_at")));
      })
      .onFailure(e -> fail(ctx, 500, "查询失败"));
  }

  /** GET /api/friends/:userId */
  private void friends(RoutingContext ctx) {
    String userId = ctx.pathParam("userId");
    long id;
    try {
      id = Long.parseLong(userId);
    } catch (NumberFormatException e) {
      fail(ctx, 400, "无效的 userId");
      return;
    }
    pgPool.preparedQuery(LIST_FRIENDS_SQL).execute(Tuple.of(id))
      .compose(rows -> buildFriendListWithOnline(rows, "friended_at")
        .map(arr -> new JsonObject().put("friends", arr)))
      .onSuccess(json -> ok(ctx, 200, json))
      .onFailure(e -> fail(ctx, 500, "查询失败"));
  }

  /** GET /api/friends/:userId/pending */
  private void pending(RoutingContext ctx) {
    String userId = ctx.pathParam("userId");
    long id;
    try {
      id = Long.parseLong(userId);
    } catch (NumberFormatException e) {
      fail(ctx, 400, "无效的 userId");
      return;
    }
    pgPool.preparedQuery(LIST_PENDING_SQL).execute(Tuple.of(id))
      .compose(rows -> buildFriendListWithOnline(rows, "requested_at")
        .map(arr -> new JsonObject().put("pending", arr)))
      .onSuccess(json -> ok(ctx, 200, json))
      .onFailure(e -> fail(ctx, 500, "查询失败"));
  }

  /**
   * 构建好友列表，在线状态通过单次 Redis SMISMEMBER 批量查询。
   */
  private Future<JsonArray> buildFriendListWithOnline(Iterable<Row> rows, String timeField) {
    List<Row> rowList = new ArrayList<>();
    List<String> userIds = new ArrayList<>();
    for (Row r : rows) {
      rowList.add(r);
      userIds.add(String.valueOf(r.getLong("id")));
    }

    if (rowList.isEmpty()) {
      return Future.succeededFuture(new JsonArray());
    }

    return batchIsOnline(userIds)
      .map(onlineFlags -> {
        JsonArray arr = new JsonArray();
        for (int i = 0; i < rowList.size(); i++) {
          Row r = rowList.get(i);
          boolean online = i < onlineFlags.size() && onlineFlags.get(i);
          arr.add(new JsonObject()
            .put("userId", String.valueOf(r.getLong("id")))
            .put("userName", r.getString("user_name"))
            .put("nickname", r.getString("nickname"))
            .put("avatar", r.getString("avatar"))
            .put("online", online)
            .put(timeField, r.getLong(timeField)));
        }
        return arr;
      });
  }

  /**
   * 批量查询在线状态。在线 = 有 session 路由 且 指向的节点存活，
   * 与推送路由(PushRouter)判断一致，崩溃残留路由不会误报在线。
   */
  private Future<List<Boolean>> batchIsOnline(List<String> userIds) {
    if (userIds == null || userIds.isEmpty()) {
      return Future.succeededFuture(List.of());
    }
    List<Future<Boolean>> futures = new ArrayList<>(userIds.size());
    for (String uid : userIds) {
      futures.add(isOnline(uid));
    }
    return Future.all(futures).map(cf -> {
      List<Boolean> result = new ArrayList<>(futures.size());
      for (int i = 0; i < futures.size(); i++) {
        result.add(cf.<Boolean>resultAt(i));
      }
      return result;
    });
  }

  private Future<Boolean> isOnline(String userId) {
    return routeTable.resolve(userId)
      .compose(nodeId -> {
        if (nodeId == null || nodeId.isEmpty()) {
          return Future.succeededFuture(false);
        }
        return routeTable.isNodeAlive(nodeId);
      })
      .recover(e -> Future.succeededFuture(false));
  }

  private void ok(RoutingContext ctx, int status, JsonObject data) {
    ctx.response().setStatusCode(status).end(data.put("code", 0).encode());
  }

  private void fail(RoutingContext ctx, int status, String msg) {
    ctx.response().setStatusCode(status).end(new JsonObject().put("code", status).put("message", msg).encode());
  }

  private boolean isBlank(String s) { return s == null || s.isBlank(); }
}
