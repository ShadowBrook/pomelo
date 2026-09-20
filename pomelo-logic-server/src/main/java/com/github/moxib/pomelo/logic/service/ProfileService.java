package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.logic.infrastructure.PgPoolFactory;
import com.github.moxib.pomelo.proto.profile.ProfileProto;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.CMD_PROFILE_UPDATE_REQ_VALUE;
import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.CMD_PROFILE_UPDATE_RESP_VALUE;

/**
 * 用户资料服务（当前仅头像）。
 * <p>
 * 头像与消息媒体同源：客户端先经 CMD_UPLOAD_REQ 拿预签名直传对象存储，
 * 再把服务端签发的对象 key 提交到这里；im_user.avatar 只存 key，
 * 所有读取出口经 {@link MediaUrlSigner#signAvatar} 换成 presigned GET URL。
 * 校验 key 形态与归属（必须是本人上传的对象），防止把他人对象或任意外链设为头像。
 * <p>
 * 不做变更扇出推送：头像变更低频，向全部好友/群成员推送扇出过大；
 * 新鲜度由客户端在展示点（进入聊天/搜索/拉成员列表）读时拉取保证。
 */
public class ProfileService extends ServiceBase {

  private static final Logger LOG = LoggerFactory.getLogger(ProfileService.class);

  private static final String UPDATE_AVATAR_SQL = """
    UPDATE im_user SET avatar = $1, updated_at = $2 WHERE id = $3
    """;

  /** avatar 列长度上限（schema 为 VARCHAR(512)） */
  private static final int MAX_AVATAR_LENGTH = 512;

  private final Pool pgPool;
  private final MediaUrlSigner mediaUrlSigner;

  public ProfileService(Vertx vertx, MediaUrlSigner mediaUrlSigner) {
    this(PgPoolFactory.get(vertx), mediaUrlSigner);
  }

  /** 供测试注入连接池 */
  ProfileService(Pool pgPool, MediaUrlSigner mediaUrlSigner) {
    this.pgPool = pgPool;
    this.mediaUrlSigner = mediaUrlSigner;
  }

  public Future<ImMessage> process(ImMessage message) {
    try {
      String userId = getUserIdFromHeaders(message);
      if (userId == null || !userId.matches("\\d{1,20}")) {
        return Future.succeededFuture(buildErrorResp(message, CMD_PROFILE_UPDATE_RESP_VALUE,
          ErrorCode.UNAUTHORIZED, "未认证用户"));
      }
      ProfileProto.ProfileUpdateReq req = decode(message, ProfileProto.ProfileUpdateReq.class);
      String avatar = req.getAvatar() == null ? "" : req.getAvatar().trim();

      if (!avatar.isEmpty()) {
        String invalid = validateAvatar(userId, avatar);
        if (invalid != null) {
          return Future.succeededFuture(buildErrorResp(message, CMD_PROFILE_UPDATE_RESP_VALUE,
            ErrorCode.BAD_REQUEST, invalid));
        }
      }

      long now = System.currentTimeMillis();
      return pgPool.preparedQuery(UPDATE_AVATAR_SQL)
        .execute(Tuple.of(avatar, now, Long.parseLong(userId)))
        .map(rows -> {
          if (rows.rowCount() == 0) {
            return buildErrorResp(message, CMD_PROFILE_UPDATE_RESP_VALUE, ErrorCode.NOT_FOUND, "用户不存在");
          }
          LOG.info("头像已更新: userId={}", userId);
          ProfileProto.ProfileUpdateResp respBody = ProfileProto.ProfileUpdateResp.newBuilder()
            .setCode(0).setMessage("success").setAvatar(mediaUrlSigner.signAvatar(avatar)).build();
          return buildResponse(message, CMD_PROFILE_UPDATE_RESP_VALUE, respBody);
        })
        .recover(e -> {
          LOG.error("头像更新失败 userId={}", userId, e);
          return Future.succeededFuture(buildErrorResp(message, CMD_PROFILE_UPDATE_RESP_VALUE,
            ErrorCode.INTERNAL_ERROR, "更新失败"));
        });
    } catch (Exception e) {
      LOG.error("资料更新请求处理失败", e);
      return Future.succeededFuture(buildErrorResp(message, CMD_PROFILE_UPDATE_RESP_VALUE,
        ErrorCode.INTERNAL_ERROR, "处理失败：" + e.getMessage()));
    }
  }

  /** 校验不通过返回错误文案，通过返回 null */
  private static String validateAvatar(String userId, String avatar) {
    if (avatar.length() > MAX_AVATAR_LENGTH) {
      return "头像对象 key 非法";
    }
    // 只接受服务端签发形态的对象 key（历史外部 URL 不允许通过此接口写入）
    if (!ObjectKeys.isWellFormed(avatar)) {
      return "头像对象 key 非法";
    }
    // 只能设置自己上传的对象，防止把他人的媒体对象设为自己的头像
    if (!userId.equals(ObjectKeys.ownerOf(avatar))) {
      return "只能设置自己上传的头像";
    }
    return null;
  }
}
