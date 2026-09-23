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

  /** 从创建到现在经过的秒数（report 前后调用即为该阶段耗时） */
  public double elapsedSeconds() {
    return (System.nanoTime() - startNanos) / 1e9;
  }

  public void report() {
    long elapsedNanos = System.nanoTime() - startNanos;
    // 只统计已完成的样本：卡死/放弃等待时未执行的槽位保持 0，不能计入总数
    long[] samples = Arrays.stream(latencies).filter(v -> v > 0).sorted().toArray();
    if (samples.length == 0) {
      System.out.printf("%s: 无完成样本 计划=%d 耗时=%.1fs errors=%d%n",
        name, latencies.length, elapsedNanos / 1e9, errors.get());
      return;
    }
    int n = samples.length;
    double qps = n * 1e9 / elapsedNanos;
    double p50 = percentile(samples, 0.50);
    double p90 = percentile(samples, 0.90);
    double p99 = percentile(samples, 0.99);
    double p999 = percentile(samples, 0.999);
    double max = samples[n - 1] / 1e6;
    System.out.printf("%s: samples=%d/%d qps=%.0f p50=%.2fms p90=%.2fms p99=%.2fms p999=%.2fms max=%.2fms errors=%d%n",
      name, n, latencies.length, qps, p50, p90, p99, p999, max, errors.get());
  }

  private static double percentile(long[] sortedSamples, double q) {
    int idx = Math.min((int) (sortedSamples.length * q), sortedSamples.length - 1);
    return sortedSamples[idx] / 1e6;
  }
}
