package com.github.moxib.pomelo.logic.id;

import io.vertx.core.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerIdResolverTest {

  @Test
  @DisplayName("env 变量解析")
  void testEnvParse() {
    assertEquals(42, EnvWorkerIdProvider.parse("42").intValue());
    assertEquals(0, EnvWorkerIdProvider.parse(" 0 ").intValue());
    assertNull(EnvWorkerIdProvider.parse(null));
    assertNull(EnvWorkerIdProvider.parse(""));
    assertNull(EnvWorkerIdProvider.parse("  "));
    assertNull(EnvWorkerIdProvider.parse("abc"));
  }

  @Test
  @DisplayName("第一个 provider 命中即返回")
  void testFirstProviderWins() {
    Future<Integer> r = WorkerIdResolver.resolve(null, List.of(
      v -> Future.succeededFuture(7),
      v -> Future.succeededFuture(8)
    ));
    assertEquals(7, r.result().intValue());
  }

  @Test
  @DisplayName("null 结果 fallback 到下一个 provider")
  void testFallbackOnNull() {
    Future<Integer> r = WorkerIdResolver.resolve(null, List.of(
      v -> Future.succeededFuture(null),
      v -> Future.succeededFuture(8)
    ));
    assertEquals(8, r.result().intValue());
  }

  @Test
  @DisplayName("失败 fallback 到下一个 provider")
  void testFallbackOnFailure() {
    Future<Integer> r = WorkerIdResolver.resolve(null, List.of(
      v -> Future.failedFuture("boom"),
      v -> Future.succeededFuture(8)
    ));
    assertEquals(8, r.result().intValue());
  }

  @Test
  @DisplayName("全部未命中且未显式放行 → 启动失败（不回退常量）")
  void testFailsInsteadOfSilentlyFallingBack() {
    Future<Integer> r = WorkerIdResolver.resolve(null, List.of(
      v -> Future.succeededFuture(null),
      v -> Future.failedFuture("boom")
    ));
    assertTrue(r.failed(),
      "多节点同时回退到同一常量会生成重复 Snowflake ID，必须拒绝启动而不是静默降级");
  }

  @Test
  @DisplayName("单节点显式放行时才允许静态 workerId")
  void testStaticWorkerIdRequiresExplicitOptIn() {
    withProperty("snowflake.allowStaticWorkerId", "true", () -> {
      Future<Integer> r = WorkerIdResolver.resolve(null, List.of(
        v -> Future.succeededFuture(null),
        v -> Future.failedFuture("boom")
      ));
      assertEquals(1, r.result().intValue());
    });
  }

  private static void withProperty(String key, String value, Runnable action) {
    String previous = System.getProperty(key);
    System.setProperty(key, value);
    try {
      action.run();
    } finally {
      if (previous == null) {
        System.clearProperty(key);
      } else {
        System.setProperty(key, previous);
      }
    }
  }
}
