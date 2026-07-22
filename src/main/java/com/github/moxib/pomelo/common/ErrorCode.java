package com.github.moxib.pomelo.common;

/**
 * 统一错误码枚举。
 * 错误信息放在 body 中，不覆盖协议层 cmd 字段。
 */
public enum ErrorCode {

  SUCCESS(0, "成功"),
  BAD_REQUEST(400, "参数无效"),
  UNAUTHORIZED(401, "未认证或未登录"),
  NOT_FOUND(404, "资源不存在"),
  CONFLICT(409, "冲突或重复操作"),
  INTERNAL_ERROR(500, "服务器内部错误"),
  NOT_IMPLEMENTED(501, "功能尚未实现"),
  UNKNOWN_CMD(1001, "未知命令"),
  DECODE_ERROR(1002, "消息解码失败"),
  ;

  private final int code;
  private final String defaultMessage;

  ErrorCode(int code, String defaultMessage) {
    this.code = code;
    this.defaultMessage = defaultMessage;
  }

  public int getCode() {
    return code;
  }

  public String getDefaultMessage() {
    return defaultMessage;
  }
}
