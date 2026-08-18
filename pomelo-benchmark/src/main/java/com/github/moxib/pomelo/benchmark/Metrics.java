package com.github.moxib.pomelo.benchmark;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 延迟统计 — 记录每个请求的端到端延迟，结束输出 QPS / P50 / P99 / max / 错误数。
 */
public class Metrics {

  private final String name;
  private final long[] latencies;
  private final AtomicInteger errors = new AtomicInteger();
  private final long startNanos;

  public Metrics(String name, int total) {
    this.name = name;
    this.latencies = new long[total];
    this.startNanos = System.nanoTime();
  }

  /** 记录第 idx 个请求的延迟（start 为请求发起的 nanoTime） */
  public void record(int idx, long start) {
    latencies[idx] = System.nanoTime() - start;
  }

  public void error() {
    errors.incrementAndGet();
  }

  public void report() {
    long elapsedNanos = System.nanoTime() - startNanos;
    int n = latencies.length;
    long[] sorted = Arrays.copyOf(latencies, n);
    Arrays.sort(sorted);
    double qps = n * 1e9 / elapsedNanos;
    double p50 = sorted[(int) (n * 0.50)] / 1e6;
    double p99 = sorted[(int) (n * 0.99)] / 1e6;
    double max = sorted[n - 1] / 1e6;
    System.out.printf("%s: total=%d qps=%.0f p50=%.2fms p99=%.2fms max=%.2fms errors=%d%n",
      name, n, qps, p50, p99, max, errors.get());
  }
}
