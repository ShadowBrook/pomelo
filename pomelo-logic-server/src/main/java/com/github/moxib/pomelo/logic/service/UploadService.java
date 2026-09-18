package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.config.ConfigHolder;
import com.github.moxib.pomelo.logic.infrastructure.ObjectPresigner;
import com.github.moxib.pomelo.logic.model.requests.UploadRequest;
import com.github.moxib.pomelo.proto.common.CommonProto;
import com.github.moxib.pomelo.proto.upload.UploadProto;
import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.CMD_UPLOAD_RESP_VALUE;

public class UploadService extends ServiceBase {

  private static final Logger LOG = LoggerFactory.getLogger(UploadService.class);

  private static final String DEFAULT_EXTENSIONS =
    "jpg,jpeg,png,gif,webp,mp4,mov,mp3,m4a,aac,amr,pdf,zip,webm,ogg,"
      + "txt,md,csv,json,doc,docx,xls,xlsx,ppt,pptx,rar,7z,tar,gz";

  private final ObjectPresigner presigner;
  private final long maxSizeBytes;
  private final Set<String> allowedExtensions;

  public UploadService(ObjectPresigner presigner) {
    this.presigner = presigner;
    this.maxSizeBytes = ConfigHolder.getLong("media.maxSizeBytes", 104857600L);
    this.allowedExtensions = loadAllowedExtensions();
  }

  private static Set<String> loadAllowedExtensions() {
    String raw = ConfigHolder.getString("media.allowedExtensions", DEFAULT_EXTENSIONS);
    Set<String> set = new HashSet<>();
    for (String s : raw.split(",")) {
      if (!s.isBlank()) {
        set.add(s.trim().toLowerCase());
      }
    }
    return set;
  }

  public Future<ImMessage> process(ImMessage message) {
    try {
      String userId = getUserIdFromHeaders(message);
      if (userId == null || !userId.matches("\\d{1,20}")) {
        return Future.succeededFuture(buildErrorResp(message, CMD_UPLOAD_RESP_VALUE, ErrorCode.UNAUTHORIZED, "未认证用户"));
      }
      UploadRequest req = decode(message, UploadRequest.class);
      if (!isMediaType(req.mediaType())) {
        return Future.succeededFuture(buildErrorResp(message, CMD_UPLOAD_RESP_VALUE, ErrorCode.BAD_REQUEST, "不支持的 media_type"));
      }
      if (req.size() <= 0 || req.size() > maxSizeBytes) {
        return Future.succeededFuture(buildErrorResp(message, CMD_UPLOAD_RESP_VALUE, ErrorCode.BAD_REQUEST, "文件大小超限"));
      }
      String ext = extractExt(req.fileName());
      if (ext == null || !allowedExtensions.contains(ext)) {
        return Future.succeededFuture(buildErrorResp(message, CMD_UPLOAD_RESP_VALUE,
          ErrorCode.BAD_REQUEST, "不支持的文件类型：" + (ext != null ? "." + ext : "文件缺少扩展名")));
      }

      String objectKey = buildObjectKey(req.mediaType(), userId, ext);
      String presignedUrl = presigner.presignPut(objectKey, req.contentType());
      long expireAt = System.currentTimeMillis() + ConfigHolder.getLong("media.putUrlTtlSeconds", 300) * 1000;

      return Future.succeededFuture(buildUploadResp(message, 0, "success", objectKey, presignedUrl, expireAt));
    } catch (Exception e) {
      LOG.error("上传请求处理失败", e);
      return Future.succeededFuture(buildErrorResp(message, CMD_UPLOAD_RESP_VALUE, ErrorCode.INTERNAL_ERROR, "上传预签名失败"));
    }
  }

  private String buildObjectKey(int mediaType, String userId, String ext) {
    return ObjectKeys.newKey(mediaTypeDir(mediaType), userId, ext);
  }

  private static String mediaTypeDir(int mediaType) {
    return switch (mediaType) {
      case CommonProto.MsgType.MSG_TYPE_IMAGE_VALUE -> "image";
      case CommonProto.MsgType.MSG_TYPE_VOICE_VALUE -> "voice";
      case CommonProto.MsgType.MSG_TYPE_VIDEO_VALUE -> "video";
      case CommonProto.MsgType.MSG_TYPE_FILE_VALUE -> "file";
      case CommonProto.MsgType.MSG_TYPE_EMOJI_VALUE -> "emoji";
      default -> "misc";
    };
  }

  private static String extractExt(String fileName) {
    if (fileName == null) {
      return null;
    }
    int dot = fileName.lastIndexOf('.');
    if (dot < 0 || dot == fileName.length() - 1) {
      return null;
    }
    return fileName.substring(dot + 1).toLowerCase();
  }

  private static boolean isMediaType(int mediaType) {
    return mediaType == CommonProto.MsgType.MSG_TYPE_IMAGE_VALUE
      || mediaType == CommonProto.MsgType.MSG_TYPE_VOICE_VALUE
      || mediaType == CommonProto.MsgType.MSG_TYPE_VIDEO_VALUE
      || mediaType == CommonProto.MsgType.MSG_TYPE_FILE_VALUE
      || mediaType == CommonProto.MsgType.MSG_TYPE_EMOJI_VALUE;
  }

  private ImMessage buildUploadResp(ImMessage request, int code, String msg,
                                    String objectKey, String presignedUrl, long expireAt) {
    UploadProto.UploadResp respBody = UploadProto.UploadResp.newBuilder()
      .setCode(code).setMessage(msg)
      .setObjectKey(objectKey).setPresignedUrl(presignedUrl).setExpireAt(expireAt).build();
    return buildResponse(request, CMD_UPLOAD_RESP_VALUE, respBody);
  }
}
