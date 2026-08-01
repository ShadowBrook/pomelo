package com.github.moxib.pomelo.seqsvr.proto;

/**
 * seqsvr 全局常量
 */
public final class SeqSvrConstants {

  private SeqSvrConstants() {}

  // 预分配步长
  public static final long SEQ_STEP = 10000;

  // 每个 Section 包含的 uid 数
  public static final int SECTION_SIZE = 100000;

  // 生产环境完整 uint32 uid 空间（概念空间，传输为 int 时需用 PRODUCTION_MAX_ID_SIZE）
  public static final long MAX_ID_SIZE = 0xffffffffL;

  // 生产环境可服务的最大 id 空间：id 以 int 传输，正数 id 最高到 Integer.MAX_VALUE - 1
  public static final int PRODUCTION_MAX_ID_SIZE = Integer.MAX_VALUE;

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

  // ==================== Alloc 响应码 ====================

  // 成功
  public static final int ALLOC_CODE_OK = 0;
  // 客户端路由表过期：该节点不拥有请求 id 的号段，响应携带最新 Router，客户端应更新后重试（最多一次）
  public static final int ALLOC_CODE_ROUTE_OUTDATED = 1;
}
