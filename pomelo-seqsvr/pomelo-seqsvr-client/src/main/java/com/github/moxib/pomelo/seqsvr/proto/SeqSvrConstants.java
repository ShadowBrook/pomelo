package com.github.moxib.pomelo.seqsvr.proto;

/**
 * seqsvr 全局常量
 */
public final class SeqSvrConstants {

  private SeqSvrConstants() {}

  // 预分配步长，对应 Go 的 SeqStep
  public static final long SEQ_STEP = 10000;

  // 每个 Section 包含的 uid 数
  public static final int SECTION_SIZE = 100000;

  // 生产环境完整 uint32 uid 空间
  public static final long MAX_ID_SIZE = 0xffffffffL;

  // 开发/测试用的缩减空间（1MB）
  public static final int DEBUG_MAX_ID_SIZE = 1 << 20;

  // 计算整个 uid 空间内有多少个 section
  public static int maxSectionSize(int maxIdSize) {
    return maxIdSize / SECTION_SIZE + 1;
  }

  // 计算 section 内的 uid 偏移
  public static int sectionIdx(int maxIdSize) {
    return maxIdSize / SECTION_SIZE;
  }
}
