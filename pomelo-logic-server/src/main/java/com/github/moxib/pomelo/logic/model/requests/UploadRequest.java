package com.github.moxib.pomelo.logic.model.requests;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.github.moxib.pomelo.proto.upload.UploadProto;

/**
 * 媒体上传预签名请求 DTO（PB UploadReq / JSON 共用）。
 * JSON 格式：{mediaType, fileName, size, contentType}。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UploadRequest(
  int mediaType,
  String fileName,
  long size,
  String contentType
) {
  public static UploadRequest fromProto(UploadProto.UploadReq proto) {
    return new UploadRequest(
      proto.getMediaType(),
      proto.getFileName(),
      proto.getSize(),
      proto.getContentType());
  }
}
