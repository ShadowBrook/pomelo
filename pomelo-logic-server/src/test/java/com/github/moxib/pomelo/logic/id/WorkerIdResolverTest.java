package com.github.moxib.pomelo.logic.id;

import io.vertx.core.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
  @DisplayName("全部未命中 fallback 到配置默认 1")
  void testFallbackToConfigDefault() {
    Future<Integer> r = WorkerIdResolver.resolve(null, List.of(
      v -> Future.succeededFuture(null),
      v -> Future.failedFuture("boom")
    ));
    assertEquals(1, r.result().intValue());
  }
}
