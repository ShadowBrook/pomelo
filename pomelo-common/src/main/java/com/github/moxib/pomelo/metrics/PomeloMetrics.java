package com.github.moxib.pomelo.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.vertx.micrometer.MicrometerMetricsFactory;

import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;

/**
 * 全局指标门面 — JVM 级单例 Prometheus registry。
 * <p>
 * seqsvr 域指标（alloc/mediate/client）与 IM 业务指标（消息量/延迟/推送）共用本 registry，
 * 各进程经 admin/API HTTP 的 {@code GET /metrics} 暴露 {@link #scrape()}。
 * 指标命名用点分（Prometheus 输出自动转下划线），label 只允许 nodeId 等低基数值，
 * 禁止业务 id（userId/conversationId/msgId）入 label。
 * <p>
 * 埋点清单与告警规则：
 * <ul>
 *   <li>seqsvr：{@code docs/2026-09-17-seqsvr-optimization-plan.md}</li>
 *   <li>IM 业务：{@code docs/2026-09-17-im-metrics-plan.md}</li>
 * </ul>
 */
public final class PomeloMetrics {

  private static final PrometheusMeterRegistry REGISTRY =
    new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

  private PomeloMetrics() {}

  public static PrometheusMeterRegistry registry() {
    return REGISTRY;
  }

  /** Prometheus 文本格式输出（供 /metrics 端点） */
  public static String scrape() {
    return REGISTRY.scrape();
  }

  public static Counter counter(String name, String... tags) {
    return Counter.builder(name).tags(tags).register(REGISTRY);
  }

  public static Timer timer(String name, String... tags) {
    return Timer.builder(name).tags(tags).register(REGISTRY);
  }

  /**
   * 延迟分位数 Timer：输出 Prometheus histogram 桶，可算 P95/P99（桶基数固定）。
   * 业务延迟指标（消息处理 / 推送投递）用这个。
   */
  public static Timer histogramTimer(String name, String... tags) {
    return Timer.builder(name)
      .tags(tags)
      .publishPercentileHistogram()
      .register(REGISTRY);
  }

  /**
   * 注册状态型 gauge。obj 传被观测对象（registry / 原子引用等），strongReference 保证
   * 注册表不会因弱引用回收而丢指标。
   */
  public static <T> void gauge(String name, T obj, ToDoubleFunction<T> fn, String... tags) {
    Gauge.builder(name, obj, fn).tags(tags).strongReference(true).register(REGISTRY);
  }

  /**
   * Vert.x 内建指标（EventBus 收发 / 线程池 / HTTP）接入同一个 Prometheus registry 的工厂，
   * 供各进程 Main 经 {@code ClusterHelper.createVertx} 传入 Vert.x 5 builder 的 withMetrics。
   */
  public static MicrometerMetricsFactory vertxMetricsFactory() {
    return new MicrometerMetricsFactory(REGISTRY);
  }

  /** 便捷：以毫秒记录耗时（延迟指标常用 System.currentTimeMillis 差值） */
  public static void recordMillis(Timer timer, long millis) {
    timer.record(Math.max(0, millis), TimeUnit.MILLISECONDS);
  }
}
