package com.github.moxib.pomelo.logic;

import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.seqsvr.client.SeqClientService;
import com.github.moxib.pomelo.logic.id.SnowflakeIdGenerator;
import com.github.moxib.pomelo.logic.infrastructure.MinioObjectPresigner;
import com.github.moxib.pomelo.logic.infrastructure.PgCallRepository;
import com.github.moxib.pomelo.logic.infrastructure.PgGroupRepository;
import com.github.moxib.pomelo.logic.infrastructure.PgMessageRepository;
import com.github.moxib.pomelo.logic.infrastructure.PgPoolFactory;
import com.github.moxib.pomelo.logic.infrastructure.RedisCallStateStore;
import com.github.moxib.pomelo.logic.infrastructure.RedisFactory;
import com.github.moxib.pomelo.logic.service.*;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;

/**
 * Logic-Server 主 Verticle。
 * 注册所有 EventBus consumer，负责将请求分发给对应的 Service。
 */
public class LogicVerticle extends VerticleBase {

  private static final Logger LOG = LoggerFactory.getLogger(LogicVerticle.class);

  private C2CService c2cService;
  private AckService ackService;
  private AuthService authService;
  private PullService pullService;
  private CtrlService ctrlService;
  private C2GService c2gService;
  private FriendService friendService;
  private GroupManagementService groupService;
  private GroupPullService groupPullService;
  private GroupAckService groupAckService;
  private UploadService uploadService;
  private CallService callService;

  private final SnowflakeIdGenerator snowflake;

  public LogicVerticle(SnowflakeIdGenerator snowflake) {
    this.snowflake = snowflake;
  }

  @Override
  public Future<?> start() {
    return RedisFactory.get(vertx).connect()
      .compose(v -> {
        var messageRepo = new PgMessageRepository(vertx);
        var seqClient = new SeqClientService(vertx);

        PushRouter pushRouter = new PushRouter(vertx);

        var presigner = new MinioObjectPresigner();
        var mediaUrlSigner = new MinioMediaUrlSigner(presigner);
        uploadService = new UploadService(presigner);

        c2cService = new C2CService(pushRouter, messageRepo, seqClient, snowflake, mediaUrlSigner);
        ackService = new AckService(pushRouter, messageRepo);
        authService = new AuthService(vertx);
        pullService = new PullService(messageRepo, mediaUrlSigner);
        ctrlService = new CtrlService();
        var groupRepo = new PgGroupRepository(vertx);
        c2gService = new C2GService(vertx, pushRouter, groupRepo, seqClient, snowflake, mediaUrlSigner);
        friendService = new FriendService(vertx, pushRouter);
        groupService = new GroupManagementService(pushRouter, groupRepo, snowflake, messageRepo);
        groupPullService = new GroupPullService(groupRepo, messageRepo, mediaUrlSigner);
        groupAckService = new GroupAckService(groupRepo, messageRepo);

        // 音视频通话：token 签发 + 房间管理 + Redis 态 + 记录落库
        var livekitTokens = new LiveKitTokenService();
        var livekitRooms = new LiveKitRoomClient(vertx, livekitTokens);
        callService = new CallService(vertx, pushRouter, groupRepo,
          new PgCallRepository(vertx), new RedisCallStateStore(RedisFactory.get(vertx)),
          livekitTokens, livekitRooms, snowflake, messageRepo, seqClient);

        var bus = vertx.eventBus();
        bus.consumer("logic.c2c",     (Message<Buffer> msg) -> dispatch(msg, c2cService::process));
        bus.consumer("logic.ack",     (Message<Buffer> msg) -> dispatch(msg, ackService::process));
        bus.consumer("logic.auth",    (Message<Buffer> msg) -> dispatch(msg, authService::process));
        bus.consumer("logic.upload",  (Message<Buffer> msg) -> dispatch(msg, uploadService::process));
        bus.consumer("logic.pull",    (Message<Buffer> msg) -> dispatch(msg, pullService::process));
        bus.consumer("logic.ctrl",    (Message<Buffer> msg) -> dispatch(msg, ctrlService::process));
        bus.consumer("logic.c2g",     (Message<Buffer> msg) -> dispatch(msg, c2gService::process));
        bus.consumer("logic.group",   (Message<Buffer> msg) -> dispatch(msg, groupService::process));
        bus.consumer("logic.gpull",   (Message<Buffer> msg) -> dispatch(msg, groupPullService::process));
        bus.consumer("logic.gack",    (Message<Buffer> msg) -> dispatch(msg, groupAckService::process));
        bus.consumer("logic.friend",  (Message<Buffer> msg) -> dispatch(msg, friendService::process));
        bus.consumer("logic.call",    (Message<Buffer> msg) -> dispatch(msg, callService::process));

        LOG.info("LogicVerticle 已启动，所有 EventBus consumer 注册完成");
        return Future.succeededFuture();
      });
  }

  /**
   * 通用分发：Buffer → ImMessage → Service → Buffer reply。
   * 用户身份由 gateway 从 AUTH_REQ token 自行解析，logic 响应不再携带内部字段。
   */
  private void dispatch(Message<Buffer> msg, Function<ImMessage, Future<ImMessage>> processor) {
    try {
      ImMessage request = new ImMessage();
      // 跳过 4 字节长度前缀（encodeToWire 的格式）
      Buffer body = msg.body();
      request.readFromWire(body.getBuffer(4, body.length()));
      processor.apply(request).onComplete(ar -> {
        if (ar.succeeded()) {
          msg.reply(ar.result().encodeToWire());
        } else {
          LOG.error("Service 处理失败: {}", ar.cause().getMessage());
          msg.fail(500, ar.cause().getMessage());
        }
      });
    } catch (Exception e) {
      LOG.error("消息解码失败", e);
      msg.fail(400, "Bad request: " + e.getMessage());
    }
  }

  @Override
  public Future<?> stop() {
    PgPoolFactory.close();
    RedisFactory.get(vertx).close();
    return Future.succeededFuture();
  }
}
