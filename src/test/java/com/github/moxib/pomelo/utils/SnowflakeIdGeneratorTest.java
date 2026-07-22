package com.github.moxib.pomelo.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SnowflakeIdGeneratorTest {

  private final SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1);

  @Test
  @DisplayName("生成单个 ID")
  void testNextId() {
    long id = generator.nextId();
    assertTrue(id > 0, "ID 应大于 0");
    System.out.println("ID: " + id);
  }

  @Test
  @DisplayName("ID 严格递增")
  void testMonotonicIncreasing() {
    long prev = generator.nextId();
    for (int i = 0; i < 10000; i++) {
      long cur = generator.nextId();
      assertTrue(cur > prev, "ID 应严格递增: " + prev + " -> " + cur);
      prev = cur;
    }
  }

  @Test
  @DisplayName("多线程并发生成 ID 唯一性")
  void testConcurrencyUniqueness() throws Exception {
    SnowflakeIdGenerator gen = new SnowflakeIdGenerator(2);
    int threadCount = 10;
    int idsPerThread = 5000;
    Set<Long> allIds = new HashSet<>(threadCount * idsPerThread);
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger failures = new AtomicInteger(0);

    for (int t = 0; t < threadCount; t++) {
      new Thread(() -> {
        try {
          for (int i = 0; i < idsPerThread; i++) {
            long id = gen.nextId();
            synchronized (allIds) {
              if (!allIds.add(id)) {
                failures.incrementAndGet();
                System.err.println("重复 ID: " + id);
              }
            }
          }
        } finally {
          latch.countDown();
        }
      }, "snowflake-test-" + t).start();
    }

    assertTrue(latch.await(30, TimeUnit.SECONDS), "应在 30s 内完成");
    assertEquals(0, failures.get(), "不应有任何重复 ID");
    assertEquals(threadCount * idsPerThread, allIds.size(), "ID 数量应匹配");
  }

  @Test
  @DisplayName("提取时间戳")
  void testExtractTimestamp() {
    long before = System.currentTimeMillis();
    long id = generator.nextId();
    long after = System.currentTimeMillis();

    long extracted = SnowflakeIdGenerator.extractTimestamp(id);
    assertTrue(extracted >= before, "提取的时间戳不应早于生成前: " + extracted + " < " + before);
    assertTrue(extracted <= after, "提取的时间戳不应晚于生成后: " + extracted + " > " + after);
  }

  @Test
  @DisplayName("提取 worker ID")
  void testExtractWorkerId() {
    SnowflakeIdGenerator gen1 = new SnowflakeIdGenerator(42);
    long id = gen1.nextId();
    assertEquals(42, SnowflakeIdGenerator.extractWorkerId(id));

    SnowflakeIdGenerator gen2 = new SnowflakeIdGenerator(1023);
    id = gen2.nextId();
    assertEquals(1023, SnowflakeIdGenerator.extractWorkerId(id));
  }

  @Test
  @DisplayName("非法 worker ID 应抛异常")
  void testInvalidWorkerId() {
    assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(-1));
    assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(1024));
  }

  @Test
  @DisplayName("worker ID 边界值")
  void testWorkerIdBoundaries() {
    assertDoesNotThrow(() -> {
      new SnowflakeIdGenerator(0).nextId();
      new SnowflakeIdGenerator(1023).nextId();
    });
  }
}
