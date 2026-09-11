package com.github.moxib.pomelo.logic.id;

import com.github.moxib.pomelo.config.ConfigHolder;
import com.github.moxib.pomelo.logic.infrastructure.RedisFactory;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.redis.client.RedisConnection;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.function.Consumer;

import static io.vertx.redis.client.Command.EVAL;

/**
 * 从 Redis 租用一个唯一的 workerId（0-1023）。
 *
 * 用 Lua 脚本原子地扫描空闲 slot（SET NX EX），成功后启动周期续期定时器；
 * 进程退出后租约随 TTL 自然过期，无需显式释放。
 */
public class RedisWorkerIdProvider implements WorkerIdProvider {

  private static final Logger LOG = LoggerFactory.getLogger(RedisWorkerIdProvider.class);

  private static final String LEASE_KEY_PREFIX = "snowflake:worker:lease";
  private static final int MAX_WORKER_ID = 1023;
  private static final long LEASE_TTL_SECONDS = 60;
  private static final long RENEW_INTERVAL_MS = 30_000;

  private static final String CLAIM_SCRIPT = """
    local prefix = KEYS[1]
    local max = tonumber(ARGV[1])
    local ttl = tonumber(ARGV[2])
    local node = ARGV[3]
    for i = 0, max do
      local key = prefix .. ':' .. i
      if redis.call('SET', key, node, 'NX', 'EX', ttl) then
        return i
      end
    end
    return -1
    """;

  /**
   * 续期必须带上归属校验：裸 EXPIRE 会在本节点已失去租约（失联超过 TTL，slot 被
   * 其他节点抢占）后顺带延长新持有者的租约，双方都以为自己合法持有同一 workerId。
   * 返回 -1 表示租约已不属于本节点。
   */
  private static final String RENEW_SCRIPT = """
    if redis.call('GET', KEYS[1]) == ARGV[1] then
      return redis.call('EXPIRE', KEYS[1], ARGV[2])
    end
    return -1
    """;

  /** 连接获取方式（测试可注入，避免依赖单例 RedisFactory） */
  interface ConnectionSupplier {
    RedisConnection get(Vertx vertx);
  }

  private final ConnectionSupplier connections;
  private final long renewIntervalMs;

  /** 租约丢失回调：此时本进程再发号就可能与他人生成重复 Snowflake ID */
  private Consumer<Integer> leaseLostHandler = workerId -> { };

  public RedisWorkerIdProvider() {
    this(vertx -> RedisFactory.get(vertx).getConnection(),
      ConfigHolder.getLong("snowflake.leaseRenewIntervalMs", RENEW_INTERVAL_MS));
  }

  RedisWorkerIdProvider(ConnectionSupplier connections, long renewIntervalMs) {
    this.connections = connections;
    this.renewIntervalMs = renewIntervalMs;
  }

  /** 注册租约丢失回调（同一实例只应注册一次） */
  public RedisWorkerIdProvider onLeaseLost(Consumer<Integer> handler) {
    if (handler != null) {
      this.leaseLostHandler = handler;
    }
    return this;
  }

  @Override
  public Future<Integer> resolve(Vertx vertx) {
    RedisConnection conn = connections.get(vertx);
    if (conn == null) {
      return Future.failedFuture("Redis 未连接，无法租用 workerId");
    }
    String node = UUID.randomUUID().toString();
    Request req = Request.cmd(EVAL)
      .arg(CLAIM_SCRIPT)
      .arg("1")
      .arg(LEASE_KEY_PREFIX)
      .arg(String.valueOf(MAX_WORKER_ID))
      .arg(String.valueOf(LEASE_TTL_SECONDS))
      .arg(node);
    return conn.send(req).compose(resp -> {
      int id = toInt(resp);
      if (id < 0) {
        return Future.failedFuture(new IllegalStateException(
          "workerId 已耗尽（0-" + MAX_WORKER_ID + "）"));
      }
      String leaseKey = LEASE_KEY_PREFIX + ":" + id;
      startRenew(vertx, conn, leaseKey, node, id);
      LOG.info("从 Redis 租用 workerId={}, leaseKey={}", id, leaseKey);
      return Future.succeededFuture(id);
    });
  }

  private void startRenew(Vertx vertx, RedisConnection conn, String leaseKey, String node, int workerId) {
    vertx.setPeriodic(renewIntervalMs, timerId ->
      renew(conn, leaseKey, node)
        .onSuccess(owned -> {
          if (!owned) {
            // 停止续期并交由上层 fail-fast：继续发号比停服危险得多
            vertx.cancelTimer(timerId);
            LOG.error("workerId={} 租约已丢失（leaseKey={}），停止续期", workerId, leaseKey);
            leaseLostHandler.accept(workerId);
          }
        })
        .onFailure(err -> LOG.warn("workerId 租约续期失败: {}, cause={}", leaseKey, err.getMessage())));
  }

  private Future<Boolean> renew(RedisConnection conn, String leaseKey, String node) {
    Request req = Request.cmd(EVAL)
      .arg(RENEW_SCRIPT)
      .arg("1")
      .arg(leaseKey)
      .arg(node)
      .arg(String.valueOf(LEASE_TTL_SECONDS));
    return conn.send(req).map(resp -> toInt(resp) >= 0);
  }

  private static int toInt(Object obj) {
    if (obj instanceof Response) {
      return ((Response) obj).toInteger();
    }
    if (obj instanceof Number) {
      return ((Number) obj).intValue();
    }
    if (obj instanceof String) {
      try {
        return Integer.parseInt((String) obj);
      } catch (NumberFormatException e) {
        return -1;
      }
    }
    return -1;
  }
}
