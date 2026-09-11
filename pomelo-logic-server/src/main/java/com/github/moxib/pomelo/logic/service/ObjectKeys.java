package com.github.moxib.pomelo.logic.service;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 媒体对象 key 的生成与解析。
 * <p>
 * 形态：{@code 类型目录/上传者userId/yyyyMMdd/对象名.扩展名}，例如
 * {@code image/100/20260910/9f2c...c1.jpg}。
 * <p>
 * 对象名用 128 位随机十六进制，而不是 Snowflake：对象名是访问媒体唯一需要知道的东西，
 * 而 Snowflake 单调递增、知道大致上传时间就能枚举。历史存量 key（Snowflake 命名）
 * 仍被 {@link #KEY_PATTERN} 接受，保证老消息可读。
 */
public final class ObjectKeys {

  private static final SecureRandom RANDOM = new SecureRandom();

  /** 对象名的两种合法形态：新版 32 位随机十六进制 / 存量 Snowflake 数字 */
  private static final Pattern KEY_PATTERN = Pattern.compile(
    "^(image|voice|video|file|emoji)/(\\d{1,20})/(\\d{8})/([0-9a-f]{32}|\\d{1,20})\\.([a-z0-9]{1,10})$");

  private ObjectKeys() {
  }

  /** 生成服务端签发的对象 key */
  public static String newKey(String typeDir, String userId, String ext) {
    String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
    return typeDir + "/" + userId + "/" + day + "/" + randomToken() + "." + ext;
  }

  /**
   * key 是否符合服务端签发形态。
   * 客户端可以把任意字符串塞进 content 的 key 字段，读侧只为形态合法的 key 签名。
   */
  public static boolean isWellFormed(String key) {
    return key != null && KEY_PATTERN.matcher(key).matches();
  }

  /** 解析上传者 userId；形态非法返回 null */
  public static String ownerOf(String key) {
    if (key == null) {
      return null;
    }
    Matcher matcher = KEY_PATTERN.matcher(key);
    return matcher.matches() ? matcher.group(2) : null;
  }

  private static String randomToken() {
    byte[] bytes = new byte[16];
    RANDOM.nextBytes(bytes);
    StringBuilder sb = new StringBuilder(32);
    for (byte b : bytes) {
      sb.append(Character.forDigit((b >> 4) & 0xF, 16));
      sb.append(Character.forDigit(b & 0xF, 16));
    }
    return sb.toString();
  }
}
