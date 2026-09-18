package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.logic.id.SnowflakeIdGenerator;
import com.github.moxib.pomelo.logic.infrastructure.MessageRepository;
import com.github.moxib.pomelo.logic.model.C2CReqContext;
import com.github.moxib.pomelo.logic.model.C2CRespResult;
import com.github.moxib.pomelo.logic.model.MessageRecord;
import com.github.moxib.pomelo.logic.model.requests.C2CRequest;
import com.github.moxib.pomelo.metrics.PomeloMetrics;
import com.github.moxib.pomelo.model.PushEnvelope;
import com.github.moxib.pomelo.proto.chat.ChatProto;
import com.github.moxib.pomelo.proto.common.CommonProto;
import com.github.moxib.pomelo.seqsvr.client.SeqClientService;
import com.google.protobuf.ByteString;
import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.*;

import java.util.Map;
import java.util.concurrent.TimeUnit;

public class C2CService extends ServiceBase {

  private static final Logger LOG = LoggerFactory.getLogger(C2CService.class);

  private final PushRouter pushRouter;
  private final MessageRepository messageRepo;
  private final SeqClientService seqClient;
  private final SnowflakeIdGenerator snowflake;
  private final MediaUrlSigner mediaUrlSigner;

  public C2CService(PushRouter pushRouter, MessageRepository messageRepo, SeqClientService seqClient,
                    SnowflakeIdGenerator snowflake, MediaUrlSigner mediaUrlSigner) {
    this.pushRouter = pushRouter;
    this.messageRepo = messageRepo;
    this.seqClient = seqClient;
    this.snowflake = snowflake;
    this.mediaUrlSigner = mediaUrlSigner;
  }

  public Future<ImMessage> process(ImMessage message) {
    try {
      C2CRequest req = decode(message, C2CRequest.class);
      C2CRequest.MessageBody msg = req.message();
      String senderUserId = extractSenderUserId(message, req.senderId());
      String recipientUserId = req.recipientId();
      String content = msg.content();
      int msgType = msg.msgType();
      long clientMsgId = resolveClientMsgId(req.messageId(), message);
      long timestamp = req.timestamp() != 0 ? req.timestamp() : System.currentTimeMillis();

      if (senderUserId == null || senderUserId.isEmpty() || recipientUserId == null || recipientUserId.isEmpty()) {
        return Future.succeededFuture(buildErrorResp(message, CMD_C2C_RESP_VALUE, ErrorCode.BAD_REQUEST, "senderId 和 recipientId 不能为空"));
      }

      Map<String, String> varHeaders = message.getVarHeaders();
      String senderUserName = varHeaders != null ? varHeaders.get("userName") : null;
      String senderNickname = varHeaders != null ? varHeaders.get("nickname") : null;

      long senderId = Long.parseLong(senderUserId);
      if (senderId == 0) {
        return Future.succeededFuture(buildErrorResp(message, CMD_C2C_RESP_VALUE, ErrorCode.UNAUTHORIZED, "发送者不存在"));
      }
      long recipientId = Long.parseLong(recipientUserId);
      if (recipientId == 0) {
        return Future.succeededFuture(buildErrorResp(message, CMD_C2C_RESP_VALUE, ErrorCode.NOT_FOUND, "接收者不存在"));
      }

      // 媒体对象归属校验：不校验则任意用户可借自发的消息给他人对象换取下载链接
      String mediaError = MediaKeyGuard.validate(msgType, content, senderUserId);
      if (mediaError != null) {
        return Future.succeededFuture(buildErrorResp(message, CMD_C2C_RESP_VALUE, ErrorCode.BAD_REQUEST, mediaError));
      }

      C2CReqContext ctx = C2CReqContext.builder()
        .messageId(clientMsgId)
        .senderId(senderId)
        .recipientId(recipientId)
        .senderUserName(senderUserName)
        .senderNickname(senderNickname)
        .msgType(msgType)
        .content(content)
        .timestamp(timestamp)
        .build();

      return doSend(ctx)
        .map(result -> buildC2CResponse(message, result));
    } catch (Exception e) {
      LOG.error("C2C 消息处理失败", e);
      return Future.succeededFuture(buildErrorResp(message, CMD_C2C_RESP_VALUE, ErrorCode.BAD_REQUEST, "消息格式错误：" + e.getMessage()));
    }
  }

  private Future<C2CRespResult> doSend(C2CReqContext ctx) {
    // 业务指标：完成回调里 record（方法返回 ≠ 处理完成），见 docs/2026-09-17-im-metrics-plan.md
    long startNanos = System.nanoTime();
    return doSendInternal(ctx)
      .onComplete(ar -> {
        PomeloMetrics.histogramTimer("im.message.process.latency", "type", "c2c")
          .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        PomeloMetrics.counter("im.message.sent.total", "type", "c2c",
          "result", ar.succeeded() ? "ok" : "fail").increment();
      });
  }

  private Future<C2CRespResult> doSendInternal(C2CReqContext ctx) {
    long snowflakeId = snowflake.nextId();
    return seqClient.fetchNextSequence(ctx.getRecipientId())
      .compose(seq -> {
        long now = System.currentTimeMillis();
        String convId = MessageRecord.buildConversationId(ctx.getSenderId(), ctx.getRecipientId());
        MessageRecord record = MessageRecord.builder()
          .id(snowflakeId)
          .senderId(ctx.getSenderId())
          .recipientId(ctx.getRecipientId())
          .conversationId(convId)
          .msgType(ctx.getMsgType())
          .content(ctx.getContent())
          .seq(seq)
          .status(0)
          .createdAt(now)
          .clientMsgId(ctx.getMessageId())
          .build();

        return messageRepo.save(record)
          .compose(inserted -> {
            if (inserted) {
              publishC2CNotify(record, ctx.getSenderUserName(), ctx.getSenderNickname());
              return Future.succeededFuture(C2CRespResult.builder()
                .code(0).message("success")
                .messageId(snowflakeId)
                .seq(seq).serverTime(now)
                .build());
            }
            // 幂等唯一键 (sender_id, client_msg_id) 冲突：客户端重试。
            // 唯一约束保证原子——并发重试也只落一行，此处查回原消息返回
            return messageRepo.findBySenderAndClientMsgId(ctx.getSenderId(), ctx.getMessageId())
              .map(existing -> {
                if (existing == null) {
                  return C2CRespResult.builder()
                    .code(0).message("success")
                    .messageId(snowflakeId)
                    .seq(seq).serverTime(now)
                    .build();
                }
                LOG.info("C2C 重试命中幂等: senderId={} clientMsgId={} existingId={}",
                  ctx.getSenderId(), ctx.getMessageId(), existing.getId());
                return C2CRespResult.builder()
                  .code(0).message("success")
                  .messageId(existing.getId())
                  .seq(existing.getSeq()).serverTime(existing.getCreatedAt())
                  .build();
              });
          });
      });
  }

  private void publishC2CNotify(MessageRecord record, String senderUserName, String senderNickname) {
    String recipientUserId = String.valueOf(record.getRecipientId());
    byte[] body = buildC2CNotifyBody(record, senderUserName, senderNickname);
    PushEnvelope env = new PushEnvelope(recipientUserId, CMD_C2C_NOTIFY_VALUE, body);
    pushRouter.push(env);
    LOG.debug("C2CNotify pushed: recipientId={} msgId={} seq={}",
      record.getRecipientId(), record.getId(), record.getSeq());
  }

  private byte[] buildC2CNotifyBody(MessageRecord record, String senderUserName, String senderNickname) {
    String signedContent = mediaUrlSigner.signContent(record.getMsgType(), record.getContent());
    CommonProto.MessageContent.Builder msgContentBuilder = CommonProto.MessageContent.newBuilder()
      .setMsgTypeValue(record.getMsgType())
      .setContent(ByteString.copyFromUtf8(signedContent != null ? signedContent : ""))
      .setTimestamp(record.getCreatedAt());
    if (senderUserName != null && !senderUserName.isEmpty()) {
      msgContentBuilder.putExt("senderUserName", senderUserName);
    }
    if (senderNickname != null && !senderNickname.isEmpty()) {
      msgContentBuilder.putExt("senderNickname", senderNickname);
    }
    ChatProto.C2CNotify notify = ChatProto.C2CNotify.newBuilder()
      .setSenderId(record.getSenderId())
      .setRecipientId(record.getRecipientId())
      .setMessage(msgContentBuilder)
      .setSeq(record.getSeq())
      .setMessageId(record.getId())
      .build();
    return notify.toByteArray();
  }

  private ImMessage buildC2CResponse(ImMessage request, C2CRespResult result) {
    ChatProto.C2CResp respBody = ChatProto.C2CResp.newBuilder()
      .setCode(result.getCode()).setMessage(result.getMessage())
      .setMessageId(result.getMessageId()).setServerTime(result.getServerTime())
      .setSeq(result.getSeq()).build();
    return buildResponse(request, CMD_C2C_RESP_VALUE, respBody);
  }

  private String extractSenderUserId(ImMessage message, String bodySenderId) {
    String hdrUserId = getUserIdFromHeaders(message);
    return hdrUserId != null ? hdrUserId : bodySenderId;
  }

  /**
   * 幂等键兜底用 Snowflake 而非墙钟毫秒：毫秒级兜底会在同毫秒内碰撞，
   * 触发唯一键冲突被吞、消息静默丢失（详见 {@link ServiceBase#parseClientMsgId}）。
   */
  private long resolveClientMsgId(long bodyMessageId, ImMessage message) {
    long cid = parseClientMsgId(bodyMessageId, message.getMessageId());
    return cid != 0 ? cid : snowflake.nextId();
  }
}
