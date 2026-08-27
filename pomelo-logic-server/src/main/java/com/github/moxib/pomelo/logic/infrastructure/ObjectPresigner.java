package com.github.moxib.pomelo.logic.infrastructure;

/**
 * 对象存储预签名器。为对象生成带时效的访问 URL，服务端不代理字节流。
 */
public interface ObjectPresigner {

  /** 生成 presigned PUT URL（上传用） */
  String presignPut(String objectKey, String contentType);

  /** 生成 presigned GET URL（下载用） */
  String presignGet(String objectKey);
}