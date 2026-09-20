package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.logic.id.SnowflakeIdGenerator;
import com.github.moxib.pomelo.logic.infrastructure.GroupMemberContextCache;
import com.github.moxib.pomelo.logic.infrastructure.GroupRepository;
import com.github.moxib.pomelo.logic.infrastructure.MessageRepository;
import com.github.moxib.pomelo.logic.model.GroupMemberRecord;
import com.github.moxib.pomelo.logic.model.GroupMsgContext;
import com.github.moxib.pomelo.logic.model.requests.C2GRequest;
import com.github.moxib.pomelo.metrics.PomeloMetrics;
import com.github.moxib.pomelo.model.PushEnvelope;
import com.github.moxib.pomelo.proto.common.CommonProto;
import com.github.moxib.pomelo.proto.group.GroupProto;
import com.github.moxib.pomelo.seqsvr.client.SeqClientService;
import com.google.protobuf.ByteString;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.*;

public class C2GService extends ServiceBase {

  private static final Logger LOG = LoggerFactory.getLogger(C2GService.class);

  private final PushRouter pushRouter;
  private final GroupRepository groupRepo;
  private final GroupMemberContextCache memberCtxCache;
  private final SeqClientService seqClient;
  private final SnowflakeIdGenerator snowflake;
  private final MediaUrlSigner mediaUrlSigner;

  public C2GService(Vertx vertx, PushRouter pushRouter, GroupRepository groupRepo,
                    SeqClientService seqClient, SnowflakeIdGenerator snowflake,
                    MediaUrlSigner mediaUrlSigner) {
    this.pushRouter = pushRouter;
    this.groupRepo = groupRepo;
    this.memberCtxCache = new GroupMemberContextCache(vertx, groupRepo);
    this.seqClient = seqClient;
    this.snowflake = snowflake;
    this.mediaUrlSigner = mediaUrlSigner;
  }

  public Future<ImMessage> process(ImMessage message) {
    try {
      Map<String, String> varHeaders = message.getVarHeaders();
      String senderUserId = varHeaders != null ? varHeaders.get("userId") : null;
      String senderUserName = varHeaders != null ? varHeaders.get("userName") : null;
      String senderNickname = varHeaders != null ? varHeaders.get("nickname") : null;

      C2GRequest req = decode(message, C2GRequest.class);
      if (req == null || req.groupId() == null || req.groupId().isEmpty()) {
        return Future.succeededFuture(buildErrorResp(message, CMD_C2G_RESP_VALUE,
          ErrorCode.BAD_REQUEST, "groupId 不能为空"));
      }
      String groupId = req.groupId();
      C2GRequest.MessageBody msg = req.message();
      if (msg == null) {
        return Future.succeededFuture(buildErrorResp(message, CMD_C2G_RESP_VALUE,
          ErrorCode.BAD_REQUEST, "message 不能为空"));
      }
      String content = msg.content();
      int msgType = msg.msgType();
      final String fExt = msg.ext();
      long clientMsgId = parseClientMsgId(req.messageId(), message.getMessageId());
      if (clientMsgId == 0) {
        // 兜底用 Snowflake 而非墙钟毫秒：毫秒级兜底会在同毫秒内碰撞，
        // 触发唯一键冲突被吞、消息静默丢失（详见 ServiceBase#parseClientMsgId）
        clientMsgId = snowflake.nextId();
      }

      if (senderUserId == null || senderUserId.isEmpty()) {
        return Future.succeededFuture(buildErrorResp(message, CMD_C2G_RESP_VALUE,
          ErrorCode.UNAUTHORIZED, "未认证用户"));
      }

      long senderNumericId = Long.parseLong(senderUserId);
      if (senderNumericId == 0) {
        return Future.succeededFuture(buildErrorResp(message, CMD_C2G_RESP_VALUE,
          ErrorCode.UNAUTHORIZED, "发送者不存在"));
      }

      // 媒体对象归属校验：不校验则任意群成员可借群消息给他人对象换取下载链接
      String mediaError = MediaKeyGuard.validate(msgType, content, senderUserId);
      if (mediaError != null) {
        return Future.succeededFuture(buildErrorResp(message, CMD_C2G_RESP_VALUE, ErrorCode.BAD_REQUEST, mediaError));
      }

      long numericGroupId = Long.parseLong(groupId);

      final String fSenderUserName = senderUserName;
      final String fSenderNickname = senderNickname;
      final String fContent = content;
      final int fMsgType = msgType;
      final long fClientMsgId = clientMsgId;

      // Caffeine 缓存群上下文（5s TTL），未命中时单次 DB 查询
      return memberCtxCache.get(numericGroupId, senderNumericId).compose(gctx -> {
        if (!gctx.groupExists()) {
          return Future.succeededFuture(buildErrorResp(message, CMD_C2G_RESP_VALUE,
            ErrorCode.NOT_FOUND, "群不存在"));
        }
        if (!gctx.isMember()) {
          return Future.succeededFuture(buildErrorResp(message, CMD_C2G_RESP_VALUE,
            ErrorCode.UNAUTHORIZED, "你不是该群成员"));
        }
        if (gctx.isMuted()) {
          return Future.succeededFuture(buildErrorResp(message, CMD_C2G_RESP_VALUE,
            ErrorCode.UNAUTHORIZED, "你已被禁言"));
        }

        GroupMsgContext ctx = GroupMsgContext.builder()
          .messageId(fClientMsgId)
          .groupId(numericGroupId)
          .groupName(gctx.groupName())
          .senderUserId(senderNumericId)
          .senderUserName(fSenderUserName)
          .senderNickname(fSenderNickname)
          .msgType(fMsgType)
          .content(fContent)
          .ext(fExt)
          .timestamp(System.currentTimeMillis())
          .build();

        return doSend(ctx, senderNumericId, numericGroupId)
          .map(result -> buildC2GResponse(message, result));
      });
    } catch (Exception e) {
      LOG.error("C2G 消息处理失败", e);
      return Future.succeededFuture(buildErrorResp(message, CMD_C2G_RESP_VALUE,
        ErrorCode.BAD_REQUEST, "消息格式错误：" + e.getMessage()));
    }
  }

  private Future<C2GRespResult> doSend(GroupMsgContext ctx, long senderNumericId, long internalGroupId) {
    // 业务指标：完成回调里 record（方法返回 ≠ 处理完成），见 docs/2026-09-17-im-metrics-plan.md
    long startNanos = System.nanoTime();
    return doSendInternal(ctx, senderNumericId, internalGroupId)
      .onComplete(ar -> {
        PomeloMetrics.histogramTimer("im.message.process.latency", "type", "c2g")
          .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        PomeloMetrics.counter("im.message.sent.total", "type", "c2g",
          "result", ar.succeeded() ? "ok" : "fail").increment();
      });
  }

  private Future<C2GRespResult> doSendInternal(GroupMsgContext ctx, long senderNumericId, long internalGroupId) {
    long snowflakeId = snowflake.nextId();
    return seqClient.fetchNextSequence(internalGroupId)
      .compose(seq -> {
        long now = System.currentTimeMillis();

        return groupRepo.saveMessage(snowflakeId, internalGroupId, senderNumericId,
            ctx.getMsgType(), ctx.getContent(), ctx.getExt(), seq, now, ctx.getMessageId())
          .compose(inserted -> {
            if (inserted) {
              pushToGroupMembers(ctx, internalGroupId, seq, snowflakeId, senderNumericId);
              return Future.succeededFuture(new C2GRespResult(0, "success", snowflakeId, ctx.getGroupId(), seq, now));
            }
            // 幂等唯一键 (group_id, sender_id, client_msg_id) 冲突：客户端重试。
            // 唯一约束保证原子——并发重试也只落一行，此处查回原消息返回
            return groupRepo.findByGroupSenderAndClientMsgId(internalGroupId, senderNumericId, ctx.getMessageId())
              .map(existing -> {
                if (existing == null) {
                  return new C2GRespResult(0, "success", snowflakeId, ctx.getGroupId(), seq, now);
                }
                LOG.info("C2G 重试命中幂等: groupId={} senderId={} clientMsgId={} existingId={}",
                  internalGroupId, senderNumericId, ctx.getMessageId(), existing.getId());
                return new C2GRespResult(0, "success",
                  existing.getId(), ctx.getGroupId(), existing.getSeq(), existing.getCreatedAt());
              });
          });
      });
  }

  private void pushToGroupMembers(GroupMsgContext ctx, long internalGroupId, long seq, long snowflakeId, long senderNumericId) {
    groupRepo.findMembers(internalGroupId).onSuccess(members -> {
      byte[] body = buildC2GNotifyBody(ctx, seq, snowflakeId);
      int pushCount = 0;
      for (GroupMemberRecord member : members) {
        if (member.getUserId() == senderNumericId) {
          continue;
        }
        String targetUserId = String.valueOf(member.getUserId());
        PushEnvelope env = new PushEnvelope(targetUserId, CMD_C2G_NOTIFY_VALUE, body);
        pushRouter.push(env);
        pushCount++;
      }
      LOG.debug("C2GNotify 推送完成: groupId={} memberCount={} seq={}",
        ctx.getGroupId(), pushCount, seq);
    }).onFailure(e -> LOG.warn("获取群成员失败 groupId={}: {}", ctx.getGroupId(), e.getMessage()));
  }

  private byte[] buildC2GNotifyBody(GroupMsgContext ctx, long seq, long snowflakeId) {
    String signedContent = mediaUrlSigner.signContent(ctx.getMsgType(), ctx.getContent());
    CommonProto.MessageContent.Builder msgContentBuilder = CommonProto.MessageContent.newBuilder()
      .setMsgTypeValue(ctx.getMsgType())
      .setContent(ByteString.copyFromUtf8(signedContent != null ? signedContent : ""))
      .setTimestamp(ctx.getTimestamp());
    if (ctx.getSenderUserName() != null && !ctx.getSenderUserName().isEmpty()) {
      msgContentBuilder.putExt("senderUserName", ctx.getSenderUserName());
    }
    if (ctx.getSenderNickname() != null && !ctx.getSenderNickname().isEmpty()) {
      msgContentBuilder.putExt("senderNickname", ctx.getSenderNickname());
    }
    // 客户端扩展元数据（如 @ 提及）随通知原样下发
    MessageExtCodec.inject(msgContentBuilder, ctx.getExt());
    return GroupProto.C2GNotify.newBuilder()
      .setSenderId(ctx.getSenderUserId())
      .setGroupId(ctx.getGroupId())
      .setMessage(msgContentBuilder)
      .setName(ctx.getGroupName() != null ? ctx.getGroupName() : "")
      .setSeq(seq)
      .setMessageId(snowflakeId)
      .build()
      .toByteArray();
  }

  private ImMessage buildC2GResponse(ImMessage request, C2GRespResult result) {
    GroupProto.C2GResp respBody = GroupProto.C2GResp.newBuilder()
      .setCode(result.code()).setMessage(result.message())
      .setMessageId(result.messageId()).setGroupId(result.groupId())
      .setServerTime(result.serverTime()).setSeq(result.seq())
      .build();
    return buildResponse(request, CMD_C2G_RESP_VALUE, respBody);
  }

  private record C2GRespResult(int code, String message, long messageId,
                                long groupId, long seq, long serverTime) {}
}
