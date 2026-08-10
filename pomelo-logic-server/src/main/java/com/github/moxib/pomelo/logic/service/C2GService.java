package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.codec.ProtobufCodec;
import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.config.SessionRouteTable;
import com.github.moxib.pomelo.logic.id.SnowflakeIdGenerator;
import com.github.moxib.pomelo.logic.infrastructure.GroupRepository;
import com.github.moxib.pomelo.logic.infrastructure.MessageRepository;
import com.github.moxib.pomelo.logic.model.GroupMemberRecord;
import com.github.moxib.pomelo.logic.model.GroupMsgContext;
import com.github.moxib.pomelo.model.PushEnvelope;
import com.github.moxib.pomelo.proto.common.CommonProto;
import com.github.moxib.pomelo.proto.group.GroupProto;
import com.github.moxib.pomelo.seqsvr.client.SeqClientService;
import com.google.protobuf.ByteString;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.*;

public class C2GService extends ServiceBase {

  private static final Logger LOG = LoggerFactory.getLogger(C2GService.class);

  private final PushRouter pushRouter;
  private final GroupRepository groupRepo;
  private final MessageRepository messageRepo;
  private final SeqClientService seqClient;
  private final SnowflakeIdGenerator snowflake;
  private final SessionRouteTable routeTable;

  public C2GService(PushRouter pushRouter, GroupRepository groupRepo,
                    MessageRepository messageRepo, SeqClientService seqClient,
                    SnowflakeIdGenerator snowflake, SessionRouteTable routeTable) {
    this.pushRouter = pushRouter;
    this.groupRepo = groupRepo;
    this.messageRepo = messageRepo;
    this.seqClient = seqClient;
    this.snowflake = snowflake;
    this.routeTable = routeTable;
  }

  public Future<ImMessage> process(ImMessage message) {
    try {
      byte codecId = message.getCodecId();

      Map<String, String> varHeaders = message.getVarHeaders();
      String senderUserId = varHeaders != null ? varHeaders.get("userId") : null;
      String senderUserName = varHeaders != null ? varHeaders.get("userName") : null;
      String senderNickname = varHeaders != null ? varHeaders.get("nickname") : null;

      String bodyStr = getBodyAsString(message);
      if (bodyStr == null) {
        return Future.succeededFuture(buildErrorResp(message, CMD_C2G_RESP_VALUE,
          ErrorCode.BAD_REQUEST, "body 不能为空"));
      }
      JsonObject body = new JsonObject(bodyStr);
      String groupId = body.getString("groupId");
      if (groupId == null || groupId.isEmpty()) {
        return Future.succeededFuture(buildErrorResp(message, CMD_C2G_RESP_VALUE,
          ErrorCode.BAD_REQUEST, "groupId 不能为空"));
      }

      JsonObject msgObj = body.getJsonObject("message");
      if (msgObj == null) {
        return Future.succeededFuture(buildErrorResp(message, CMD_C2G_RESP_VALUE,
          ErrorCode.BAD_REQUEST, "message 不能为空"));
      }

      String content = msgObj.getString("content", "");
      int msgType = msgObj.getInteger("msgType", 1);
      long clientMsgId = body.getLong("messageId", 0L);
      if (clientMsgId == 0) {
        try { clientMsgId = Long.parseLong(message.getMessageId()); }
        catch (NumberFormatException e) { clientMsgId = System.currentTimeMillis(); }
      }

      if (senderUserId == null || senderUserId.isEmpty()) {
        return Future.succeededFuture(buildErrorResp(message, CMD_C2G_RESP_VALUE,
          ErrorCode.UNAUTHORIZED, "未认证用户"));
      }

      final String fSenderUserId = senderUserId;
      final String fSenderUserName = senderUserName;
      final String fSenderNickname = senderNickname;
      final String fGroupId = groupId;
      final String fContent = content;
      final int fMsgType = msgType;
      final long fClientMsgId = clientMsgId;
      final byte fCodecId = codecId;

      return resolveId(fSenderUserId).compose(senderNumericId -> {
        if (senderNumericId == 0) {
          return Future.succeededFuture(buildErrorResp(message, CMD_C2G_RESP_VALUE,
            ErrorCode.UNAUTHORIZED, "发送者不存在"));
        }
        return groupRepo.findByGroupId(fGroupId).compose(group -> {
          if (group == null) {
            return Future.succeededFuture(buildErrorResp(message, CMD_C2G_RESP_VALUE,
              ErrorCode.NOT_FOUND, "群不存在"));
          }
          long internalGroupId = group.getId();

          return groupRepo.isMember(internalGroupId, senderNumericId).compose(isMember -> {
            if (!isMember) {
              return Future.succeededFuture(buildErrorResp(message, CMD_C2G_RESP_VALUE,
                ErrorCode.UNAUTHORIZED, "你不是该群成员"));
            }

            GroupMsgContext ctx = GroupMsgContext.builder()
              .messageId(fClientMsgId)
              .groupId(fGroupId)
              .groupName(group.getName())
              .senderUserId(fSenderUserId)
              .senderUserName(fSenderUserName)
              .senderNickname(fSenderNickname)
              .msgType(fMsgType)
              .content(fContent)
              .timestamp(System.currentTimeMillis())
              .codecId(fCodecId)
              .build();

            return doSend(ctx, senderNumericId, internalGroupId)
              .map(result -> buildC2GResponse(message, fCodecId, result));
          });
        });
      });
    } catch (Exception e) {
      LOG.error("C2G 消息处理失败", e);
      return Future.succeededFuture(buildErrorResp(message, CMD_C2G_RESP_VALUE,
        ErrorCode.BAD_REQUEST, "消息格式错误：" + e.getMessage()));
    }
  }

  private Future<C2GRespResult> doSend(GroupMsgContext ctx, long senderNumericId, long internalGroupId) {
    long snowflakeId = snowflake.nextId();
    return seqClient.fetchNextSequence(internalGroupId)
      .compose(seq -> {
        long now = System.currentTimeMillis();

        return groupRepo.saveMessage(snowflakeId, internalGroupId, senderNumericId,
            ctx.getMsgType(), ctx.getContent(), seq, now)
          .map(inserted -> {
            if (inserted) {
              pushToGroupMembers(ctx, internalGroupId, seq, snowflakeId, senderNumericId);
            }
            return new C2GRespResult(0, "success", snowflakeId, ctx.getGroupId(), seq, now);
          });
      });
  }

  private void pushToGroupMembers(GroupMsgContext ctx, long internalGroupId, long seq, long snowflakeId, long senderNumericId) {
    groupRepo.findMembers(internalGroupId).onSuccess(members -> {
      int pushCount = 0;
      for (GroupMemberRecord member : members) {
        if (member.getUserId() == senderNumericId) {
          continue;
        }
        // 推送目标用 member.nanoId（im_user.user_id NanoID）
        String targetNanoId = member.getNanoId();
        if (targetNanoId == null) {
          continue;
        }
        routeTable.resolveCodec(targetNanoId)
          .onSuccess(recipientCodec -> {
            byte[] pbBody;
            byte pushCodec;
            if (recipientCodec == ProtobufCodec.CODEC_ID) {
              CommonProto.MessageContent msgContent = CommonProto.MessageContent.newBuilder()
                .setMsgTypeValue(ctx.getMsgType())
                .setContent(ByteString.copyFromUtf8(ctx.getContent() != null ? ctx.getContent() : ""))
                .build();
              GroupProto.C2GNotify notify = GroupProto.C2GNotify.newBuilder()
                .setSenderId(ctx.getSenderUserId())
                .setGroupId(ctx.getGroupId())
                .setMessage(msgContent)
                .setSeq(seq)
                .build();
              pbBody = notify.toByteArray();
              pushCodec = 0;
            } else {
              JsonObject json = new JsonObject();
              json.put("senderId", ctx.getSenderUserId());
              json.put("groupId", ctx.getGroupId());
              if (ctx.getGroupName() != null) json.put("name", ctx.getGroupName());
              if (ctx.getSenderUserName() != null) json.put("senderUserName", ctx.getSenderUserName());
              if (ctx.getSenderNickname() != null) json.put("senderNickname", ctx.getSenderNickname());
              JsonObject jsonMsg = new JsonObject();
              jsonMsg.put("msgType", ctx.getMsgType());
              jsonMsg.put("content", ctx.getContent() != null ? ctx.getContent() : "");
              json.put("message", jsonMsg);
              json.put("id", String.valueOf(snowflakeId));
              json.put("seq", seq);
              json.put("createdAt", System.currentTimeMillis());
              pbBody = json.toBuffer().getBytes();
              pushCodec = 1;
            }
            PushEnvelope env = new PushEnvelope(targetNanoId, CMD_C2G_NOTIFY_VALUE, pbBody, pushCodec);
            pushRouter.push(env);
          });
        pushCount++;
      }
      LOG.debug("C2GNotify 推送完成: groupId={} memberCount={} seq={}",
        ctx.getGroupId(), pushCount, seq);
    }).onFailure(e -> LOG.warn("获取群成员失败 groupId={}: {}", ctx.getGroupId(), e.getMessage()));
  }

  private ImMessage buildC2GResponse(ImMessage request, byte codecId, C2GRespResult result) {
    Object respBody;
    if (codecId == ProtobufCodec.CODEC_ID) {
      respBody = GroupProto.C2GResp.newBuilder()
        .setCode(result.code()).setMessage(result.message())
        .setMessageId(result.messageId()).setGroupId(result.groupId())
        .setServerTime(result.serverTime()).setSeq(result.seq())
        .build();
    } else {
      respBody = new JsonObject()
        .put("code", result.code()).put("message", result.message())
        .put("messageId", String.valueOf(result.messageId()))
        .put("groupId", result.groupId())
        .put("serverTime", result.serverTime())
        .put("seq", result.seq());
    }
    return buildResponse(request, CMD_C2G_RESP_VALUE, respBody);
  }

  private Future<Long> resolveId(String userId) {
    try { return Future.succeededFuture(Long.parseLong(userId)); }
    catch (NumberFormatException e) { return messageRepo.findUserId(userId); }
  }

  private record C2GRespResult(int code, String message, long messageId,
                                String groupId, long seq, long serverTime) {}
}
