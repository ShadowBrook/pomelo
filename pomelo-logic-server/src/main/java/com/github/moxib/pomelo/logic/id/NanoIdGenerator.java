package com.github.moxib.pomelo.logic.id;

import com.github.moxib.pomelo.config.ConfigHolder;

import java.security.SecureRandom;

/**
 * NanoID 生成器——短小、URL 安全、全局唯一的字符串 ID。
 *
 * <p>默认配置：21 位字符，64 字符字母表（A-Za-z0-9_-）。
 * 生成 21 位 NanoID 仅需 ~36 bytes 随机数，碰撞概率约 1/2^126。</p>
 *
 * <p>与 {@link SnowflakeIdGenerator} / {@link RedisIdGenerator} 的关系：</p>
 * <ul>
 *   <li>{@code SnowflakeIdGenerator}：用户表 {@code id}（BIGINT 主键）</li>
 *   <li>{@code RedisIdGenerator}：消息表 {@code seq}（全局递增）</li>
 *   <li>{@code NanoIdGenerator}：用户表 {@code user_id}（对外唯一标识符）</li>
 * </ul>
 */
public final class NanoIdGenerator {

  /** 默认 ID 长度 */
  private static final int DEFAULT_SIZE = 21;

  /** 默认字母表（URL 安全，无混淆字符） */
  private static final char[] DEFAULT_ALPHABET =
    "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz_-".toCharArray();

  private static final SecureRandom RANDOM = new SecureRandom();

  private final int size;
  private final char[] alphabet;
  private final int mask;
  private final int step;

  /** 使用默认配置创建（21 位，64 字符字母表）。 */
  public NanoIdGenerator() {
    this(DEFAULT_SIZE, DEFAULT_ALPHABET);
  }

  /**
   * @param size     ID 长度
   * @param alphabet 字母表
   */
  public NanoIdGenerator(int size, char[] alphabet) {
    if (alphabet.length == 0 || alphabet.length > 256) {
      throw new IllegalArgumentException("alphabet 长度必须在 1-256 之间");
    }
    this.size = size;
    this.alphabet = alphabet.clone();
    // 计算掩码和步长
    int mask = (2 << (int) (Math.log(alphabet.length - 1) / Math.log(2))) - 1;
    int step = (int) Math.ceil(1.6 * mask * size / alphabet.length);
    this.mask = mask;
    this.step = step;
  }

  /** 生成一个 NanoID。 */
  public String generate() {
    char[] result = new char[size];
    byte[] bytes = new byte[step];
    int pos = 0;

    while (pos < size) {
      RANDOM.nextBytes(bytes);
      for (int i = 0; i < step && pos < size; i++) {
        int index = bytes[i] & mask;
        if (index < alphabet.length) {
          result[pos++] = alphabet[index];
        }
      }
    }
    return new String(result);
  }

  // ---- 便捷方法 ----

  private static final NanoIdGenerator DEFAULT =
    new NanoIdGenerator(ConfigHolder.getInt("nanoid.size", 21), DEFAULT_ALPHABET);

  /** 使用默认配置生成一个 NanoID（21 位）。 */
  public static String next() {
    return DEFAULT.generate();
  }
}
