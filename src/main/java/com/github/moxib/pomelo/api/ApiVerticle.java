package com.github.moxib.pomelo.api;

import at.favre.lib.crypto.bcrypt.BCrypt;
import com.github.moxib.pomelo.service.PgPoolFactory;
import com.github.moxib.pomelo.service.RedisFactory;
import com.github.moxib.pomelo.service.RedisOnlineStatus;
import com.github.moxib.pomelo.service.TokenService;
import com.github.moxib.pomelo.utils.NanoIdGenerator;
import com.github.moxib.pomelo.utils.SnowflakeIdGenerator;
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
  private static final int PORT = Integer.parseInt(System.getProperty("api.http.port", "8080"));

  private static final String INSERT_USER_SQL = """
    INSERT INTO im_user (id, user_id, user_name, nickname, avatar, password, status, created_at, updated_at)
    VALUES ($1, $2, $3, $4, $5, $6, 0, $7, $7) ON CONFLICT (user_name) DO NOTHING
    """;
  private static final String FIND_USER_SQL = """
    SELECT user_id, user_name, nickname, avatar, status, created_at FROM im_user WHERE user_id = $1
    """;
  private static final String FIND_BY_USERNAME_SQL = """
    SELECT id, user_id, user_name, nickname, avatar, password, status, created_at
    FROM im_user WHERE user_name = $1
    """;
  private static final String LIST_FRIENDS_SQL = """
    SELECT u.user_id, u.user_name, u.nickname, u.avatar, u.status, f.created_at AS friended_at
    FROM im_friend f
    JOIN im_user me ON f.user_id = me.id
    JOIN im_user u  ON f.friend_id = u.id
    WHERE me.user_id = $1 AND f.status = 1 ORDER BY f.created_at DESC
    """;
  private static final String LIST_PENDING_SQL = """
    SELECT u.user_id, u.user_name, u.nickname, u.avatar, u.status, f.created_at AS requested_at
    FROM im_friend f
    JOIN im_user me ON f.friend_id = me.id
    JOIN im_user u  ON f.user_id = u.id
    WHERE me.user_id = $1 AND f.status = 0 ORDER BY f.created_at DESC
    """;

  private final SnowflakeIdGenerator snowflake =
    new SnowflakeIdGenerator(Integer.getInteger("snowflake.worker.id", 1));

  private HttpServer server;
  private Pool pgPool;

  @Override
  public Future<?> start() {
    pgPool = PgPoolFactory.get(vertx);

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
        return server.requestHandler(router).listen(PORT)
          .onSuccess(v2 -> LOG.info("ApiVerticle 已启动，端口: {}", PORT))
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

  /** POST /api/user/register — userName + password，返回系统生成的 NanoID userId */
  private void register(RoutingContext ctx) {
    JsonObject body = ctx.body().asJsonObject();
    String userName = body.getString("userName");
    String nickname = body.getString("nickname", userName);
    String password = body.getString("password");
    String avatar = body.getString("avatar", "");

    if (isBlank(userName) || isBlank(password)) { fail(ctx, 400, "userName 和 password 不能为空"); return; }

    long id = snowflake.nextId();
    String userId = NanoIdGenerator.next();
    String hash = BCrypt.withDefaults().hashToString(12, password.toCharArray());
    long now = System.currentTimeMillis();

    pgPool.preparedQuery(INSERT_USER_SQL).execute(Tuple.of(id, userId, userName, nickname, avatar, hash, now))
      .onSuccess(r -> {
        if (r.rowCount() > 0) {
          String token = TokenService.get(vertx).generate(userId);
          LOG.info("注册成功: userId={} userName={}", userId, userName);
          ok(ctx, 201, new JsonObject().put("userId", userId).put("userName", userName).put("token", token));
        } else {
          fail(ctx, 409, "用户名已存在");
        }
      })
      .onFailure(e -> fail(ctx, 500, "注册失败"));
  }

  /** POST /api/user/login — userName + password，返回 userId（NanoID） */
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
        String userId = r.getString("user_id");
        String token = TokenService.get(vertx).generate(userId, r.getLong("id"),
          r.getString("user_name"), r.getString("nickname"));
        ok(ctx, 200, new JsonObject()
          .put("userId", userId)
          .put("userName", r.getString("user_name"))
          .put("nickname", r.getString("nickname"))
          .put("avatar", r.getString("avatar"))
          .put("token", token));
      })
      .onFailure(e -> fail(ctx, 500, "登录失败"));
  }

  /** GET /api/user/:userId/profile */
  private void profile(RoutingContext ctx) {
    String userId = ctx.pathParam("userId");
    pgPool.preparedQuery(FIND_USER_SQL).execute(Tuple.of(userId))
      .onSuccess(rows -> {
        if (rows.size() == 0) { fail(ctx, 404, "用户不存在"); return; }
        Row r = rows.iterator().next();
        ok(ctx, 200, new JsonObject()
          .put("userId", r.getString("user_id"))
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
    pgPool.preparedQuery(LIST_FRIENDS_SQL).execute(Tuple.of(userId))
      .compose(rows -> buildFriendListWithOnline(rows, "friended_at")
        .map(arr -> new JsonObject().put("friends", arr)))
      .onSuccess(json -> ok(ctx, 200, json))
      .onFailure(e -> fail(ctx, 500, "查询失败"));
  }

  /** GET /api/friends/:userId/pending */
  private void pending(RoutingContext ctx) {
    String userId = ctx.pathParam("userId");
    pgPool.preparedQuery(LIST_PENDING_SQL).execute(Tuple.of(userId))
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
      userIds.add(r.getString("user_id"));
    }

    if (rowList.isEmpty()) {
      return Future.succeededFuture(new JsonArray());
    }

    return RedisOnlineStatus.get(vertx).batchIsOnline(userIds)
      .map(onlineFlags -> {
        JsonArray arr = new JsonArray();
        for (int i = 0; i < rowList.size(); i++) {
          Row r = rowList.get(i);
          boolean online = i < onlineFlags.size() && onlineFlags.get(i);
          arr.add(new JsonObject()
            .put("userId", r.getString("user_id"))
            .put("userName", r.getString("user_name"))
            .put("nickname", r.getString("nickname"))
            .put("avatar", r.getString("avatar"))
            .put("online", online)
            .put(timeField, r.getLong(timeField)));
        }
        return arr;
      });
  }

  private void ok(RoutingContext ctx, int status, JsonObject data) {
    ctx.response().setStatusCode(status).end(data.put("code", 0).encode());
  }

  private void fail(RoutingContext ctx, int status, String msg) {
    ctx.response().setStatusCode(status).end(new JsonObject().put("code", status).put("message", msg).encode());
  }

  private boolean isBlank(String s) { return s == null || s.isBlank(); }
}
