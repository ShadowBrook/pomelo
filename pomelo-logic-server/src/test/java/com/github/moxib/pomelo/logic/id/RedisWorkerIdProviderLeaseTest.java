package com.github.moxib.pomelo.logic.id;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisConnection;
import io.vertx.redis.client.RedisOptions;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * workerId 租约续期的归属校验（真实 Redis + 真实 Lua）。
 * <p>
 * 裸 EXPIRE 续期有个危险副作用：进程与 Redis 失联超过 TTL、slot 被别的节点抢占后，
 * 本进程的续期会顺带延长新持有者的租约，双方都以为自己合法持有同一 workerId，
 * 于是各自用同一 workerId 发号，生成重复 Snowflake ID。
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(VertxExtension.class)
@DisplayName("workerId 租约归属")
class RedisWorkerIdProviderLeaseTest {

  private static final long RENEW_INTERVAL_MS = 50;
  private static final long AWAIT_MS = 5000;

  @Container
  static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
    .withExposedPorts(6379)
    .withReuse(true);

  private static final String LEASE_KEY_PREFIX = "snowflake:worker:lease:";

  @Test
  @DisplayName("租约被他人抢占后续期不再续、并触发 fail-fast 回调")
  void detectsLeaseLoss(Vertx vertx) throws Exception {
    RedisConnection conn = connect(vertx);
    try {
      RedisWorkerIdProvider provider = new RedisWorkerIdProvider(v -> conn, RENEW_INTERVAL_MS);
      AtomicInteger lostId = new AtomicInteger(-1);
      CountDownLatch lost = new CountDownLatch(1);
      provider.onLeaseLost(id -> {
        lostId.set(id);
        lost.countDown();
      });

      int workerId = provider.resolve(vertx).toCompletionStage().toCompletableFuture().get(AWAIT_MS, TimeUnit.MILLISECONDS);
      assertTrue(workerId >= 0);

      // 模拟：本进程失联超过 TTL，slot 被其他节点抢占
      String leaseKey = LEASE_KEY_PREFIX + workerId;
      await(conn.send(Request.cmd(Command.SET).arg(leaseKey).arg("other-node")));

      assertTrue(lost.await(AWAIT_MS, TimeUnit.MILLISECONDS),
        "租约已不属于本节点时必须触发 fail-fast，而不是继续续期");
      assertEquals(workerId, lostId.get());

      // 抢占者的租约不得被本进程续期
      Response ttl = await(conn.send(Request.cmd(Command.TTL).arg(leaseKey)));
      assertEquals(-1L, ttl.toLong(), "无 TTL 的抢占者租约不应被本进程续期成带 TTL 的租约");
    } finally {
      conn.close();
    }
  }

  @Test
  @DisplayName("仍持有租约时正常续期，不触发 fail-fast")
  void keepsLeaseWhileStillOwner(Vertx vertx) throws Exception {
    RedisConnection conn = connect(vertx);
    try {
      RedisWorkerIdProvider provider = new RedisWorkerIdProvider(v -> conn, RENEW_INTERVAL_MS);
      CountDownLatch lost = new CountDownLatch(1);
      provider.onLeaseLost(id -> lost.countDown());

      int workerId = provider.resolve(vertx).toCompletionStage().toCompletableFuture().get(AWAIT_MS, TimeUnit.MILLISECONDS);
      String leaseKey = LEASE_KEY_PREFIX + workerId;

      // 跑过数个续期周期：值仍是本进程的 node token（非 others），TTL 被续上
      Thread.sleep(RENEW_INTERVAL_MS * 3);
      assertFalse(lost.await(10, TimeUnit.MILLISECONDS), "仍持有租约时不应触发 fail-fast");

      Response value = await(conn.send(Request.cmd(Command.GET).arg(leaseKey)));
      assertNotNull(value, "仍持有租约时 key 不应消失");
      Response ttl = await(conn.send(Request.cmd(Command.TTL).arg(leaseKey)));
      assertTrue(ttl.toLong() > 0L, "续期应保持 TTL");
    } finally {
      conn.close();
    }
  }

  private static RedisConnection connect(Vertx vertx) throws Exception {
    String uri = String.format("redis://%s:%d", redis.getHost(), redis.getMappedPort(6379));
    Redis client = Redis.createClient(vertx, new RedisOptions().setConnectionString(uri));
    return client.connect().toCompletionStage().toCompletableFuture().get(AWAIT_MS, TimeUnit.MILLISECONDS);
  }

  private static Response await(Future<Response> future) throws Exception {
    return future.toCompletionStage().toCompletableFuture().get(AWAIT_MS, TimeUnit.MILLISECONDS);
  }
}
