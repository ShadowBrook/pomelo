package com.github.moxib.pomelo.logic.infrastructure;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 群成员上下文缓存——对 {@link GroupRepository#getGroupMemberContext(long, long)} 的结果做 Caffeine 本地缓存。
 *
 * <p>采用 Vert.x 官方 how-to 推荐模式：
 * <ol>
 *   <li>{@code .executor(cmd -> context.runOnContext(v -> cmd.run()))} — 让 Caffeine loader
 *       跑在 Vert.x event loop 上，避免 ForkJoinPool 上下文切换</li>
 *   <li>{@code CompletableFuture.supplyAsync(() -> ..., exec).thenComposeAsync(F.identity(), exec)}
 *       — 将 Vert.x {@link Future} 桥接到 {@link CompletableFuture}</li>
 * </ol></p>
 *
 * <p>参考：
 * <a href="https://vertx.io/docs/howtos/async-loading-cache-caffeine-howto/">Async Loading Cache with Caffeine</a></p>
 */
public class GroupMemberContextCache {

  private final AsyncCache<GroupMemberCacheKey, GroupMemberContext> cache;
  private final BiFunction<GroupMemberCacheKey, Executor, CompletableFuture<GroupMemberContext>> loader;

  public GroupMemberContextCache(Vertx vertx, GroupRepository groupRepo) {
    this.loader = (key, exec) ->
      CompletableFuture.supplyAsync(() ->
        groupRepo.getGroupMemberContext(key.groupId(), key.userId())
          .toCompletionStage(), exec)
        .thenComposeAsync(Function.identity(), exec);

    this.cache = Caffeine.newBuilder()
      .expireAfterWrite(5, TimeUnit.SECONDS)
      .maximumSize(10_000)
      .recordStats()
      .executor(cmd -> vertx.getOrCreateContext().runOnContext(v -> cmd.run()))
      .buildAsync();
  }

  /**
   * 获取群成员上下文，缓存命中直接返回，未命中通过 loader 自动加载。
   */
  public Future<GroupMemberContext> get(long groupId, long userId) {
    return Future.fromCompletionStage(
      cache.get(new GroupMemberCacheKey(groupId, userId), loader));
  }
}
