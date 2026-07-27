package com.github.moxib.pomelo.logic.id;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Snowflake ID 生成器（64-bit 分布式唯一 ID）。
 *
 * <pre>
 * ┌─┬──────────────────────────┬─────────────┬──────────────────────┐
 * │0│     timestamp (41b)       │ worker (10b)│    sequence (12b)     │
 * │ │  ms since 2024-01-01     │  0 ~ 1023   │     0 ~ 4095         │
 * └─┴──────────────────────────┴─────────────┴──────────────────────┘
 * </pre>
 *
 * <ul>
 *   <li>Epoch: {@code 2024-01-01T00:00:00Z}（1704067200000），可用到 2093 年</li>
 *   <li>Worker ID: 系统属性 {@code snowflake.worker.id}，默认 1</li>
 *   <li>时钟回拨 ≤ 5s：等待重试；> 5s：抛出 IllegalStateException</li>
 *   <li>线程安全，无外部依赖</li>
 * </ul>
 *
 * <p>与 {@link IdGenerator} / {@link RedisIdGenerator} 的关系：</p>
 * <ul>
 *   <li>{@code IdGenerator} + {@code RedisIdGenerator}：服务端消息 seq，全局递增</li>
 *   <li>{@code SnowflakeIdGenerator}：用户 id，去中心化唯一，不依赖 Redis</li>
 * </ul>
 */
public class SnowflakeIdGenerator {

  private static final Logger LOG = LoggerFactory.getLogger(SnowflakeIdGenerator.class);

  /** 起始时间戳（2024-01-01T00:00:00Z） */
  public static final long EPOCH = 1704067200000L;

  /** worker ID 占用位数 */
  private static final int WORKER_ID_BITS = 10;

  /** 序列号占用位数 */
  private static final int SEQUENCE_BITS = 12;

  /** worker ID 最大值 (1023) */
  private static final int MAX_WORKER_ID = (1 << WORKER_ID_BITS) - 1;

  /** 序列号掩码 (4095) */
  private static final int SEQUENCE_MASK = (1 << SEQUENCE_BITS) - 1;

  /** 时间戳左移位数 */
  private static final int TIMESTAMP_SHIFT = WORKER_ID_BITS + SEQUENCE_BITS;

  /** worker ID 左移位数 */
  private static final int WORKER_ID_SHIFT = SEQUENCE_BITS;

  /** 时钟回拨容忍上限（毫秒） */
  private static final long MAX_BACKWARD_MS = 5000L;

  /** 时钟检查间隔（毫秒） */
  private static final long CLOCK_CHECK_INTERVAL_MS = 10L;

  private final int workerId;

  private final AtomicLong lastTimestamp;

  private final AtomicLong sequence;

  /**
   * @param workerId worker ID（0–1023），通常设为 1
   * @throws IllegalArgumentException 如果 workerId 不在范围内
   */
  public SnowflakeIdGenerator(int workerId) {
    if (workerId < 0 || workerId > MAX_WORKER_ID) {
      throw new IllegalArgumentException("workerId 必须在 0–" + MAX_WORKER_ID + " 之间，当前: " + workerId);
    }
    this.workerId = workerId;
    this.lastTimestamp = new AtomicLong(-1L);
    this.sequence = new AtomicLong(0);
  }

  /**
   * 生成下一个 ID。线程安全，本地计算无 I/O。
   *
   * @return 全局唯一的 64-bit Snowflake ID
   * @throws IllegalStateException 时钟回拨超过 5 秒
   */
  public synchronized long nextId() {
    long timestamp = currentTimeMillis();

    // 时钟回拨检测
    long lastTs = lastTimestamp.get();
    if (timestamp < lastTs) {
      long backward = lastTs - timestamp;
      if (backward <= MAX_BACKWARD_MS) {
        LOG.warn("时钟回拨 {}ms，等待恢复… 当前时间戳={} 上次时间戳={}", backward, timestamp, lastTs);
        try {
          while (timestamp < lastTs) {
            Thread.sleep(CLOCK_CHECK_INTERVAL_MS);
            timestamp = currentTimeMillis();
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("时钟回拨等待被中断", e);
        }
        LOG.info("时钟恢复，继续生成 ID");
      } else {
        throw new IllegalStateException(
          String.format("时钟回拨超过 %dms：上次 %d，当前 %d，差值 %dms",
            MAX_BACKWARD_MS, lastTs, timestamp, backward));
      }
    }

    if (timestamp == lastTs) {
      // 同毫秒内，序列递增
      long seq = (sequence.incrementAndGet()) & SEQUENCE_MASK;
      if (seq == 0) {
        // 该毫秒序列耗尽，自旋等待下一毫秒
        while (timestamp <= lastTs) {
          timestamp = currentTimeMillis();
        }
        sequence.set(0);
      }
    } else {
      // 新的毫秒，序列重置
      sequence.set(0);
    }

    lastTimestamp.set(timestamp);
    long seq = sequence.get();

    return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
         | ((long) workerId << WORKER_ID_SHIFT)
         | seq;
  }

  /**
   * 从 ID 中提取时间戳（epoch 偏移量）。
   */
  public static long extractTimestamp(long id) {
    return (id >> TIMESTAMP_SHIFT) + EPOCH;
  }

  /**
   * 从 ID 中提取 worker ID。
   */
  public static int extractWorkerId(long id) {
    return (int) ((id >> WORKER_ID_SHIFT) & MAX_WORKER_ID);
  }

  private long currentTimeMillis() {
    return System.currentTimeMillis();
  }

  // ---- 供测试使用 ----

  long lastTimestampForTest() {
    return lastTimestamp.get();
  }

  long sequenceForTest() {
    return sequence.get();
  }
}
