package com.github.moxib.pomelo.logic.id;

import com.github.moxib.pomelo.logic.infrastructure.RedisFactory;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.RedisConnection;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

import static io.vertx.redis.client.Command.EVAL;
import static io.vertx.redis.client.Command.EXPIRE;

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

  @Override
  public Future<Integer> resolve(Vertx vertx) {
    RedisConnection conn = RedisFactory.get(vertx).getConnection();
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
      startRenew(vertx, conn, leaseKey, node);
      LOG.info("从 Redis 租用 workerId={}, leaseKey={}", id, leaseKey);
      return Future.succeededFuture(id);
    });
  }

  private void startRenew(Vertx vertx, RedisConnection conn, String leaseKey, String node) {
    vertx.setPeriodic(RENEW_INTERVAL_MS, id -> {
      Request req = Request.cmd(EXPIRE)
        .arg(leaseKey)
        .arg(String.valueOf(LEASE_TTL_SECONDS));
      conn.send(req).onFailure(err ->
        LOG.warn("workerId 租约续期失败: {}, cause={}", leaseKey, err.getMessage()));
    });
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
