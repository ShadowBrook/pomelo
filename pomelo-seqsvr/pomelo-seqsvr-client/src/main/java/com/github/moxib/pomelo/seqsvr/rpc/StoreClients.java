package com.github.moxib.pomelo.seqsvr.rpc;

import io.vertx.core.eventbus.EventBus;

import java.util.Arrays;
import java.util.List;

/**
 * StoreAccessor 工厂 — 根据配置选择单副本或 NRW 多副本实现。
 * <p>
 * 配置项（由调用方从 ConfigHolder 读取后传入）：
 * <ul>
 *   <li>{@code replicas}：逗号分隔的副本地址前缀，如 {@code "seqsvr.store.r1,seqsvr.store.r2,seqsvr.store.r3"}；
 *       为空 / 空白 → 单副本 {@link EventBusStoreClient}</li>
 *   <li>{@code w} / {@code r}：写 / 读仲裁数</li>
 * </ul>
 */
public final class StoreClients {

  private StoreClients() {}

  /**
   * @param singlePrefix   单副本地址前缀（默认 seqsvr.store）
   * @param replicasConfig 逗号分隔的多副本前缀，空则单副本
   */
  public static StoreAccessor create(EventBus eventBus, String singlePrefix, String replicasConfig, int w, int r) {
    List<String> prefixes = parsePrefixes(replicasConfig);
    if (prefixes.isEmpty()) {
      return new EventBusStoreClient(eventBus, singlePrefix);
    }
    return new ReplicatedStoreClient(eventBus, prefixes, w, r);
  }

  private static List<String> parsePrefixes(String config) {
    if (config == null || config.isBlank()) {
      return List.of();
    }
    return Arrays.stream(config.split(","))
      .map(String::trim)
      .filter(s -> !s.isEmpty())
      .toList();
  }
}
