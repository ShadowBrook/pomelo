package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.codec.CodecRegistry;
import com.github.moxib.pomelo.codec.ProtobufCodec;
import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.seqsvr.client.SeqClientService;
import com.github.moxib.pomelo.logic.infrastructure.MessageRepository;
import com.github.moxib.pomelo.logic.model.C2CReqContext;
import com.github.moxib.pomelo.logic.model.C2CRespResult;
import com.github.moxib.pomelo.logic.model.MessageRecord;
import com.github.moxib.pomelo.logic.model.requests.C2CRequest;
import com.github.moxib.pomelo.model.PushEnvelope;
import com.github.moxib.pomelo.proto.chat.ChatProto;
import com.github.moxib.pomelo.proto.common.CommonProto;
import com.google.protobuf.ByteString;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.*;

import java.util.Map;

public class C2CService extends ServiceBase {

  private static final Logger LOG = LoggerFactory.getLogger(C2CService.class);

  private final Vertx vertx;
  private final PushRouter pushRouter;
  private final MessageRepository messageRepo;
  private final SeqClientService seqClient;
  private final CodecRegistry codecRegistry;

  public C2CService(Vertx vertx, PushRouter pushRouter, MessageRepository messageRepo, SeqClientService seqClient) {
    this.vertx = vertx;
    this.pushRouter = pushRouter;
    this.messageRepo = messageRepo;
    this.seqClient = seqClient;
    this.codecRegistry = new CodecRegistry();
    codecRegistry.registerProtobuf(CMD_C2C_REQ_VALUE, ChatProto.C2CReq.parser(), C2CRequest::fromProto, C2CRequest.class);
    codecRegistry.registerJson(CMD_C2C_REQ_VALUE, C2CRequest.class);
  }

  public Future<ImMessage> process(ImMessage message) {
    try {
      byte codecId = message.getCodecId();
      C2CRequest req = decode(codecRegistry, message, C2CRequest.class);
      C2CRequest.MessageBody msg = req.message();
      String senderUserId = extractSenderUserId(message, req.senderId());
      String recipientUserId = req.recipientId();
      String content = msg.content();
      int msgType = msg.msgType();
      long clientMsgId = req.messageId() != 0 ? req.messageId() : parseWireMessageId(message);
      long timestamp = req.timestamp() != 0 ? req.timestamp() : System.currentTimeMillis();

      if (senderUserId == null || senderUserId.isEmpty() || recipientUserId == null || recipientUserId.isEmpty()) {
        return Future.succeededFuture(buildErrorResp(message, CMD_C2C_RESP_VALUE, ErrorCode.BAD_REQUEST, "senderId 和 recipientId 不能为空"));
      }

      // 从 varHeaders 提取发送者显示名（Gateway 转发时已附加）
      Map<String, String> varHeaders = message.getVarHeaders();
      String senderUserName = varHeaders != null ? varHeaders.get("userName") : null;
      String senderNickname = varHeaders != null ? varHeaders.get("nickname") : null;

      // NanoID → numeric id：优先 parseLong，失败则查 DB
      final String fSenderUserId = senderUserId;
      final String fRecipientUserId = recipientUserId;
      final String fSenderUserName = senderUserName;
      final String fSenderNickname = senderNickname;
      final long fTimestamp = timestamp;
      final long fClientMsgId = clientMsgId;
      final int fMsgType = msgType;
      final String fContent = content;
      final byte fCodecId = codecId;

      return resolveId(senderUserId).compose(senderId -> {
        if (senderId == 0) {
          return Future.succeededFuture(buildErrorResp(message, CMD_C2C_RESP_VALUE, ErrorCode.UNAUTHORIZED, "发送者不存在"));
        }
        return resolveId(recipientUserId).compose(recipientId -> {
          if (recipientId == 0) {
            return Future.succeededFuture(buildErrorResp(message, CMD_C2C_RESP_VALUE, ErrorCode.NOT_FOUND, "接收者不存在"));
          }

          C2CReqContext ctx = C2CReqContext.builder()
            .messageId(fClientMsgId)
            .senderId(senderId)
            .recipientId(recipientId)
            .senderUserId(fSenderUserId)
            .recipientUserId(fRecipientUserId)
            .senderUserName(fSenderUserName)
            .senderNickname(fSenderNickname)
            .msgType(fMsgType)
            .content(fContent)
            .timestamp(fTimestamp)
            .build();

          return doSend(ctx)
            .map(result -> buildC2CResponse(message, fCodecId, result));
        });
      });
    } catch (Exception e) {
      LOG.error("C2C 消息处理失败", e);
      return Future.succeededFuture(buildErrorResp(message, CMD_C2C_RESP_VALUE, ErrorCode.BAD_REQUEST, "消息格式错误：" + e.getMessage()));
    }
  }

  private Future<C2CRespResult> doSend(C2CReqContext ctx) {
    return seqClient.fetchNextSequence(ctx.getSenderId())
      .compose(seq -> {
        long now = System.currentTimeMillis();
        String convId = MessageServiceImpl.buildConversationId(ctx.getSenderId(), ctx.getRecipientId());
        MessageRecord record = MessageRecord.builder()
          .id(ctx.getMessageId())
          .senderId(ctx.getSenderId())
          .recipientId(ctx.getRecipientId())
          .conversationId(convId)
          .msgType(ctx.getMsgType())
          .content(ctx.getContent())
          .seq(seq)
          .status(0)
          .createdAt(now)
          .build();

        return messageRepo.save(record)
          .map(inserted -> {
            if (inserted) {
              publishC2CNotify(record, ctx.getSenderUserId(), ctx.getRecipientUserId(),
                ctx.getSenderUserName(), ctx.getSenderNickname());
            }
            return C2CRespResult.builder()
              .code(0).message("success")
              .messageId(ctx.getMessageId())
              .seq(seq).serverTime(now)
              .build();
          });
      });
  }

  private void publishC2CNotify(MessageRecord record, String senderUserId, String recipientUserId,
                                  String senderUserName, String senderNickname) {
    // PB body
    CommonProto.MessageContent msgContent = CommonProto.MessageContent.newBuilder()
      .setMsgTypeValue(record.getMsgType())
      .setContent(ByteString.copyFromUtf8(record.getContent() != null ? record.getContent() : ""))
      .build();
    ChatProto.C2CNotify notify = ChatProto.C2CNotify.newBuilder()
      .setSenderId(senderUserId)
      .setRecipientId(String.valueOf(record.getRecipientId()))
      .setMessage(msgContent)
      .setSeq(record.getSeq())
      .setMessageId(record.getId())
      .build();

    // JSON body（与旧 MessageServiceImpl.pushToRecipient 的 JSON 分支对齐）
    JsonObject json = new JsonObject();
    json.put("senderId", senderUserId);
    json.put("recipientId", recipientUserId);
    if (senderUserName != null) json.put("senderUserName", senderUserName);
    if (senderNickname != null) json.put("senderNickname", senderNickname);
    json.put("conversationId", record.getConversationId());
    json.put("seq", record.getSeq());
    JsonObject jsonMsgContent = new JsonObject();
    json.put("message", jsonMsgContent);
    jsonMsgContent.put("msgType", record.getMsgType());
    jsonMsgContent.put("content", record.getContent() != null ? record.getContent() : "");
    json.put("id", record.getId());
    json.put("messageId", record.getId());
    json.put("createdAt", record.getCreatedAt());

    PushEnvelope env = new PushEnvelope(
      recipientUserId,
      CMD_C2C_NOTIFY_VALUE,
      notify.toByteArray(),
      json.toBuffer().getBytes(),
      (byte) 0
    );
    pushRouter.push(env);
    LOG.debug("C2CNotify pushed: recipientId={} msgId={} seq={}", record.getRecipientId(), record.getId(), record.getSeq());
  }

  private ImMessage buildC2CResponse(ImMessage request, byte codecId, C2CRespResult result) {
    Object respBody = codecId == ProtobufCodec.CODEC_ID
      ? ChatProto.C2CResp.newBuilder()
          .setCode(result.getCode()).setMessage(result.getMessage())
          .setMessageId(result.getMessageId()).setServerTime(result.getServerTime())
          .setSeq(result.getSeq()).build()
      : result;
    return buildResponse(request, CMD_C2C_RESP_VALUE, respBody);
  }

  private Future<Long> resolveId(String userId) {
    // 先尝试解析为数字 id，否则查 DB
    try { return Future.succeededFuture(Long.parseLong(userId)); }
    catch (NumberFormatException e) { return messageRepo.findUserId(userId); }
  }

  private String extractSenderUserId(ImMessage message, String bodySenderId) {
    String hdrUserId = getUserIdFromHeaders(message);
    return hdrUserId != null ? hdrUserId : bodySenderId;
  }

  private long parseWireMessageId(ImMessage message) {
    try { return Long.parseLong(message.getMessageId()); }
    catch (NumberFormatException e) { return System.currentTimeMillis(); }
  }
}
