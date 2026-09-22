package com.github.moxib.pomelo.logic;

import com.github.moxib.pomelo.config.ConfigHolder;
import com.github.moxib.pomelo.config.JwtTokenParser;
import com.github.moxib.pomelo.config.SessionRouteTable;
import com.github.moxib.pomelo.config.TlsConfig;
import com.github.moxib.pomelo.logic.infrastructure.MinioObjectPresigner;
import com.github.moxib.pomelo.logic.infrastructure.PgPoolFactory;
import com.github.moxib.pomelo.logic.infrastructure.RedisFactory;
import com.github.moxib.pomelo.logic.infrastructure.TokenService;
import com.github.moxib.pomelo.logic.id.SnowflakeIdGenerator;
import com.github.moxib.pomelo.logic.service.MailService;
import com.github.moxib.pomelo.logic.service.MediaUrlSigner;
import com.github.moxib.pomelo.logic.service.MinioMediaUrlSigner;
import com.github.moxib.pomelo.logic.service.TermsOfService;
import com.github.moxib.pomelo.metrics.PomeloMetrics;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.hashing.HashingStrategy;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.RedisConnection;
import io.vertx.redis.client.Request;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/**
 * HTTP API Verticle — 短连接业务接口。
 * 注册、登录、资料查询、好友列表等场景。
 * 端口：{@code api.http.port}（默认 8080）。
 */
public class ApiVerticle extends VerticleBase {

  private static final Logger LOG = LoggerFactory.getLogger(ApiVerticle.class);

  private static final String INSERT_USER_SQL = """
    INSERT INTO im_user (id, user_name, nickname, avatar, email, password, status, created_at, updated_at)
    VALUES ($1, $2, $3, $4, $5, $6, 0, $7, $7) ON CONFLICT (user_name) DO NOTHING
    """;
  private static final String FIND_USER_SQL = """
    SELECT id, user_name, nickname, avatar, signature, status, created_at FROM im_user WHERE id = $1
    """;
  private static final String FIND_PASSWORD_SQL = """
    SELECT password FROM im_user WHERE id = $1
    """;
  private static final String UPDATE_PASSWORD_SQL = """
    UPDATE im_user SET password = $1, updated_at = $2 WHERE id = $3
    """;
  private static final String UPDATE_EMAIL_SQL = """
    UPDATE im_user SET email = $1, updated_at = $2 WHERE id = $3
    """;
  private static final String FIND_RESET_TARGET_SQL = """
    SELECT id, email FROM im_user WHERE user_name = $1
    """;
  private static final String FIND_BY_USERNAME_SQL = """
    SELECT id, user_name, nickname, avatar, signature, email, password, status, created_at
    FROM im_user WHERE user_name = $1
    """;
  private static final String LIST_FRIENDS_SQL = """
    SELECT u.id, u.user_name, u.nickname, u.avatar, u.signature, u.status, f.created_at AS friended_at
    FROM im_friend f
    JOIN im_user me ON f.user_id = me.id
    JOIN im_user u  ON f.friend_id = u.id
    WHERE me.id = $1 AND f.status = 1 ORDER BY f.created_at DESC
    """;
  private static final String LIST_PENDING_SQL = """
    SELECT u.id, u.user_name, u.nickname, u.avatar, u.signature, u.status, f.created_at AS requested_at
    FROM im_friend f
    JOIN im_user me ON f.friend_id = me.id
    JOIN im_user u  ON f.user_id = u.id
    WHERE me.id = $1 AND f.status = 0 ORDER BY f.created_at DESC
    """;

  private final HashingStrategy strategy = HashingStrategy.load();
  private final SecureRandom random = new SecureRandom();
  private JwtTokenParser jwtParser;

  private int port;
  private final SnowflakeIdGenerator snowflake;

  private HttpServer server;
  private Pool pgPool;
  private SessionRouteTable routeTable;
  // 头像列存对象 key，出囗统一换 presigned GET URL（与消息媒体同一套签名器）
  private MediaUrlSigner mediaUrlSigner;
  // 找回密码验证码邮件（未配置 SMTP 时相关接口返回 503，不影响其他功能）
  private MailService mailService;

  /** 邮箱格式（不追求 RFC 完备，挡住明显非法输入即可） */
  private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
  /** 找回密码：验证码尝试次数上限 */
  private static final int RESET_MAX_ATTEMPTS = 5;

  public ApiVerticle(SnowflakeIdGenerator snowflake) {
    this.snowflake = snowflake;
  }

  @Override
  public Future<?> start() {
    this.port = ConfigHolder.getInt("api.http.port", 8888);
    pgPool = PgPoolFactory.get(vertx);
    routeTable = new SessionRouteTable(vertx);
    mediaUrlSigner = new MinioMediaUrlSigner(new MinioObjectPresigner());
    jwtParser = new JwtTokenParser(vertx);
    mailService = new MailService(vertx);
    if (!mailService.configured()) {
      LOG.warn("邮件服务未配置（mail.host/mail.from 为空）：找回密码接口将返回 503");
    }

    return RedisFactory.get(vertx).connect()
      .compose(v -> {
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        router.get("/api/health").handler(this::health);
        // Prometheus 指标（seqsvr 客户端 + Vert.x 内建）；compose 端口绑 127.0.0.1，不外泄
        router.get("/metrics").handler(this::metrics);
        router.post("/api/user/register").handler(this::register);
        router.post("/api/user/login").handler(this::login);
        router.post("/api/user/change-password").handler(this::changePassword);
        router.post("/api/user/bind-email").handler(this::bindEmail);
        // 找回密码：request 发验证码邮件（Redis 存码 + 冷却），confirm 校验后改密
        router.post("/api/user/password-reset/request").handler(this::passwordResetRequest);
        router.post("/api/user/password-reset/confirm").handler(this::passwordResetConfirm);
        // 服务条款（公开；三端共用一份文案，避免各自维护漂移）
        router.get("/api/legal/terms").handler(this::terms);
        router.get("/api/user/:userId/profile").handler(this::profile);
        router.get("/api/friends/:userId").handler(this::friends);
        router.get("/api/friends/:userId/pending").handler(this::pending);
        // LiveKit webhook 兜底（participant_left/room_finished）：验签在 CallService 侧完成
        router.post("/api/livekit/webhook").handler(this::livekitWebhook);

        // TLS：启用后客户端需使用 https://
        HttpServerOptions serverOptions = new HttpServerOptions();
        PemKeyCertOptions pem = TlsConfig.pemKeyCert();
        if (pem != null) {
          serverOptions.setSsl(true).setKeyCertOptions(pem);
        }
        server = vertx.createHttpServer(serverOptions);
        return server.requestHandler(router).listen(port)
          .onSuccess(v2 -> LOG.info("ApiVerticle 已启动，端口: {}（TLS={}）", port, TlsConfig.enabled()))
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

  private void metrics(RoutingContext ctx) {
    ctx.response()
      .putHeader("content-type", "text/plain; version=0.0.4; charset=utf-8")
      .end(PomeloMetrics.scrape());
  }

  /**
   * POST /api/livekit/webhook — LiveKit 事件兜底。
   * 原始 body + Authorization 头经 EventBus 转给 CallService 验签处理，
   * 回复值为 HTTP 状态码。此端点不校验 JWT 登录态（LiveKit 的签名即凭证）。
   */
  private void livekitWebhook(RoutingContext ctx) {
    String body = ctx.body().asString();
    String auth = ctx.request().getHeader("Authorization");
    vertx.eventBus().<Integer>request("logic.call.webhook",
        new JsonObject().put("body", body == null ? "" : body).put("auth", auth))
      .onSuccess(reply -> ctx.response().setStatusCode(reply.body()).end())
      .onFailure(e -> ctx.response().setStatusCode(503).end());
  }

  /** POST /api/user/register — userName + password（可选 email，用于找回密码），返回 Snowflake userId */
  private void register(RoutingContext ctx) {
    JsonObject body = ctx.body().asJsonObject();
    String userName = body.getString("userName");
    String nickname = body.getString("nickname", userName);
    String password = body.getString("password");
    String avatar = body.getString("avatar", "");
    String email = trimToEmpty(body.getString("email"));

    if (isBlank(userName) || isBlank(password)) { fail(ctx, 400, "userName 和 password 不能为空"); return; }
    if (!email.isEmpty() && !EMAIL_PATTERN.matcher(email).matches()) { fail(ctx, 400, "邮箱格式不正确"); return; }

    long id = snowflake.nextId();
    String userId = String.valueOf(id);
    String hash = hashPassword(password);
    long now = System.currentTimeMillis();

    pgPool.preparedQuery(INSERT_USER_SQL).execute(Tuple.of(id, userName, nickname, avatar, email, hash, now))
      .onSuccess(r -> {
        if (r.rowCount() > 0) {
          String token = TokenService.get(vertx).generate(userId, userName, nickname, "");
          LOG.info("注册成功: userId={} userName={} hasEmail={}", userId, userName, !email.isEmpty());
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
        if (!strategy.verify(hash, password)) {
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
          .put("avatar", mediaUrlSigner.signAvatar(r.getString("avatar")))
          .put("signature", r.getString("signature"))
          .put("email", r.getString("email") == null ? "" : r.getString("email"))
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
          .put("avatar", mediaUrlSigner.signAvatar(r.getString("avatar")))
          .put("signature", r.getString("signature"))
          .put("status", r.getInteger("status"))
          .put("createdAt", r.getLong("created_at")));
      })
      .onFailure(e -> fail(ctx, 500, "查询失败"));
  }

  /**
   * POST /api/user/change-password — 修改密码（Authorization: Bearer JWT）。
   * 校验原密码后写新 hash；成功后已签发 token 仍然有效（JWT 无状态，客户端自行重新登录换取新 token）。
   */
  private void changePassword(RoutingContext ctx) {
    String auth = ctx.request().getHeader("Authorization");
    if (auth == null || !auth.startsWith("Bearer ")) {
      fail(ctx, 401, "请先登录");
      return;
    }
    JsonObject body = ctx.body().asJsonObject();
    String oldPassword = body == null ? null : body.getString("oldPassword");
    String newPassword = body == null ? null : body.getString("newPassword");
    if (isBlank(oldPassword) || isBlank(newPassword) || newPassword.length() < 6) {
      fail(ctx, 400, "新密码至少 6 位");
      return;
    }
    jwtParser.validate(auth.substring(7).trim())
      .onSuccess(claims -> {
        long id = claims != null ? claims.getLong("id", 0L) : 0L;
        if (id == 0) {
          fail(ctx, 401, "登录已过期");
          return;
        }
        pgPool.preparedQuery(FIND_PASSWORD_SQL).execute(Tuple.of(id))
          .onSuccess(rows -> {
            if (rows.size() == 0) {
              fail(ctx, 404, "用户不存在");
              return;
            }
            String hash = rows.iterator().next().getString("password");
            if (!strategy.verify(hash, oldPassword)) {
              fail(ctx, 401, "原密码错误");
              return;
            }
            pgPool.preparedQuery(UPDATE_PASSWORD_SQL)
              .execute(Tuple.of(hashPassword(newPassword), System.currentTimeMillis(), id))
              .onSuccess(r -> ok(ctx, 200, new JsonObject().put("message", "密码已修改")))
              .onFailure(e -> fail(ctx, 500, "修改失败"));
          })
          .onFailure(e -> fail(ctx, 500, "修改失败"));
      })
      .onFailure(e -> fail(ctx, 401, "登录已过期"));
  }

  /** GET /api/legal/terms — 服务条款（公开接口，登录页也要能看） */
  private void terms(RoutingContext ctx) {
    ok(ctx, 200, new JsonObject()
      .put("title", TermsOfService.TITLE)
      .put("version", TermsOfService.VERSION)
      .put("content", TermsOfService.CONTENT));
  }

  /**
   * POST /api/user/bind-email — 绑定/更换邮箱（Authorization: Bearer JWT）。
   * 邮箱用于找回密码；暂不做邮件验证（测试项目取舍，见服务条款）。
   */
  private void bindEmail(RoutingContext ctx) {
    String auth = ctx.request().getHeader("Authorization");
    if (auth == null || !auth.startsWith("Bearer ")) {
      fail(ctx, 401, "请先登录");
      return;
    }
    JsonObject body = ctx.body().asJsonObject();
    String email = body == null ? null : trimToEmpty(body.getString("email"));
    if (email.isEmpty() || !EMAIL_PATTERN.matcher(email).matches()) {
      fail(ctx, 400, "邮箱格式不正确");
      return;
    }
    jwtParser.validate(auth.substring(7).trim())
      .onSuccess(claims -> {
        long id = claims != null ? claims.getLong("id", 0L) : 0L;
        if (id == 0) {
          fail(ctx, 401, "登录已过期");
          return;
        }
        pgPool.preparedQuery(UPDATE_EMAIL_SQL)
          .execute(Tuple.of(email, System.currentTimeMillis(), id))
          .onSuccess(r -> {
            LOG.info("邮箱已绑定: userId={}", id);
            ok(ctx, 200, new JsonObject().put("message", "邮箱已绑定").put("email", email));
          })
          .onFailure(e -> {
            // 23505 = 唯一索引冲突（同一邮箱被其他账号占用）
            if (e.getMessage() != null && e.getMessage().contains("idx_user_email")) {
              fail(ctx, 409, "该邮箱已被其他账号绑定");
            } else {
              fail(ctx, 500, "绑定失败");
            }
          });
      })
      .onFailure(e -> fail(ctx, 401, "登录已过期"));
  }

  /**
   * POST /api/user/password-reset/request — 发送找回密码验证码。
   * 验证码存 Redis（TTL 见 mail.codeTtlSeconds），同账号发信有冷却（mail.sendCooldownSeconds）。
   * 测试项目取舍：用户名不存在/未绑定邮箱时直接告知，便于自助排查（注册接口本就开放，谈不上用户枚举）。
   */
  private void passwordResetRequest(RoutingContext ctx) {
    JsonObject body = ctx.body().asJsonObject();
    String userName = body == null ? null : trimToEmpty(body.getString("userName"));
    if (userName.isEmpty()) {
      fail(ctx, 400, "userName 不能为空");
      return;
    }
    if (!mailService.configured()) {
      fail(ctx, 503, "邮件服务未配置，无法发送验证码（请联系管理员）");
      return;
    }
    pgPool.preparedQuery(FIND_RESET_TARGET_SQL).execute(Tuple.of(userName))
      .onSuccess(rows -> {
        if (rows.size() == 0) {
          fail(ctx, 404, "用户名不存在");
          return;
        }
        Row r = rows.iterator().next();
        long id = r.getLong("id");
        String email = r.getString("email");
        if (email == null || email.isBlank()) {
          fail(ctx, 400, "该账号未绑定邮箱，请先登录后在设置中绑定");
          return;
        }
        String cooldownKey = resetKey("cd", id);
        redis().send(Request.cmd(Command.SET).arg(cooldownKey).arg("1")
            .arg("EX").arg(mailService.sendCooldownSeconds()).arg("NX"))
          .onSuccess(resp -> {
            if (resp == null || resp.toString().equals("null")) {
              fail(ctx, 429, "请求过于频繁，请稍后再试");
              return;
            }
            String code = String.format("%06d", random.nextInt(1_000_000));
            redis().send(Request.cmd(Command.SET).arg(resetKey("code", id)).arg(code)
                .arg("EX").arg(mailService.codeTtlSeconds())).onFailure(e -> LOG.warn("验证码写入 Redis 失败", e));
            redis().send(Request.cmd(Command.DEL).arg(resetKey("try", id))).onFailure(e -> { /* 尝试计数清理失败不阻断 */ });
            long ttlMinutes = Math.max(1, mailService.codeTtlSeconds() / 60);
            mailService.send(email, "Pomelo 找回密码验证码",
                "你的验证码是：" + code + "\n\n" + ttlMinutes + " 分钟内有效，请勿转发给他人。\n"
                  + "若非本人操作，请忽略本邮件。")
              .onSuccess(v -> ok(ctx, 200, new JsonObject()
                .put("message", "验证码已发送至 " + maskEmail(email) + "，请查收（含垃圾箱）")
                .put("email", maskEmail(email))
                .put("ttlSeconds", mailService.codeTtlSeconds())))
              .onFailure(e -> {
                LOG.warn("找回密码验证码发送失败: userId={} err={}", id, e.getMessage());
                fail(ctx, 502, "验证码发送失败，请稍后重试");
              });
          })
          .onFailure(e -> {
            LOG.warn("冷却检查失败（Redis 异常）", e);
            fail(ctx, 500, "服务暂不可用，请稍后重试");
          });
      })
      .onFailure(e -> fail(ctx, 500, "服务暂不可用，请稍后重试"));
  }

  /**
   * POST /api/user/password-reset/confirm — 校验验证码并重置密码。
   * 连续输错 {@value #RESET_MAX_ATTEMPTS} 次作废验证码，需重新获取。
   */
  private void passwordResetConfirm(RoutingContext ctx) {
    JsonObject body = ctx.body().asJsonObject();
    String userName = body == null ? null : trimToEmpty(body.getString("userName"));
    String code = body == null ? null : trimToEmpty(body.getString("code"));
    String newPassword = body == null ? null : body.getString("newPassword");
    if (userName.isEmpty() || code.isEmpty()) {
      fail(ctx, 400, "userName 和 code 不能为空");
      return;
    }
    if (newPassword == null || newPassword.length() < 6) {
      fail(ctx, 400, "新密码至少 6 位");
      return;
    }
    pgPool.preparedQuery(FIND_RESET_TARGET_SQL).execute(Tuple.of(userName))
      .onSuccess(rows -> {
        if (rows.size() == 0) {
          fail(ctx, 404, "用户名不存在");
          return;
        }
        long id = rows.iterator().next().getLong("id");
        redis().send(Request.cmd(Command.GET).arg(resetKey("code", id)))
          .onSuccess(resp -> {
            String stored = resp == null ? null : resp.toString();
            if (stored == null || stored.equals("null")) {
              fail(ctx, 400, "验证码已过期，请重新获取");
              return;
            }
            if (!stored.equals(code)) {
              redis().send(Request.cmd(Command.INCR).arg(resetKey("try", id)))
                .onSuccess(c -> {
                  long attempts = c == null ? 1 : Long.parseLong(c.toString());
                  if (attempts >= RESET_MAX_ATTEMPTS) {
                    redis().send(Request.cmd(Command.DEL).arg(resetKey("code", id)));
                    fail(ctx, 429, "错误次数过多，验证码已作废，请重新获取");
                  } else {
                    fail(ctx, 401, "验证码错误");
                  }
                })
                .onFailure(e -> fail(ctx, 401, "验证码错误"));
              return;
            }
            pgPool.preparedQuery(UPDATE_PASSWORD_SQL)
              .execute(Tuple.of(hashPassword(newPassword), System.currentTimeMillis(), id))
              .onSuccess(r -> {
                redis().send(Request.cmd(Command.DEL).arg(resetKey("code", id)).arg(resetKey("try", id))
                    .arg(resetKey("cd", id))).onFailure(e -> { /* 清理失败仅影响冷却时间 */ });
                LOG.info("找回密码成功: userId={}", id);
                ok(ctx, 200, new JsonObject().put("message", "密码已重置，请用新密码登录"));
              })
              .onFailure(e -> fail(ctx, 500, "重置失败"));
          })
          .onFailure(e -> fail(ctx, 500, "服务暂不可用，请稍后重试"));
      })
      .onFailure(e -> fail(ctx, 500, "服务暂不可用，请稍后重试"));
  }

  private static String resetKey(String kind, long userId) {
    return "pwdreset:" + kind + ":" + userId;
  }

  /** 日志与响应里不落完整邮箱 */
  static String maskEmail(String email) {
    if (email == null || email.isEmpty()) {
      return "";
    }
    int at = email.indexOf('@');
    if (at <= 1) {
      return "*" + (at >= 0 ? email.substring(at) : "");
    }
    return email.charAt(0) + "***" + email.substring(at);
  }

  /** Redis 连接（start 阶段已 connect；此处只取句柄，命令失败由各调用点 recover） */
  private RedisConnection redis() {
    return RedisFactory.get(vertx).getConnection();
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
            .put("avatar", mediaUrlSigner.signAvatar(r.getString("avatar")))
            .put("signature", r.getString("signature"))
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

  /**
   * 在线 = 任一端型有 session 路由 且 指向的节点存活，
   * 与推送路由(PushRouter)判断一致，崩溃残留路由不会误报在线。
   */
  private Future<Boolean> isOnline(String userId) {
    return routeTable.resolveAll(userId)
      .compose(routes -> {
        if (routes.isEmpty()) {
          return Future.succeededFuture(false);
        }
        return Future.all(routes.values().stream().map(routeTable::isNodeAlive).toList())
          .map(cf -> {
            for (int i = 0; i < cf.size(); i++) {
              if (cf.<Boolean>resultAt(i)) {
                return true;
              }
            }
            return false;
          });
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

  /** null 安全 trim（避免各处重复判空） */
  private static String trimToEmpty(String s) { return s == null ? "" : s.trim(); }

  /** 用 Vert.x HashingStrategy（PBKDF2）哈希密码，返回自包含哈希串（含算法/参数/salt） */
  private String hashPassword(String password) {
    byte[] salt = new byte[32];
    random.nextBytes(salt);
    String saltStr = Base64.getEncoder().encodeToString(salt);
    return strategy.hash("pbkdf2", null, saltStr, password);
  }
}
