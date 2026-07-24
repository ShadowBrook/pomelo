package com.github.moxib.pomelo.utils;

import com.github.moxib.pomelo.config.ConfigHolder;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisConnection;
import io.vertx.redis.client.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static io.vertx.redis.client.Command.EVAL;

/**
 * 基于 Redis 和本地 AtomicLong 的分布式 ID 生成器
 *
 * 参考 Redisson 的 IdGenerator 实现，使用 Lua 脚本保证原子性
 *
 * 实现原理：
 * 1. 使用 Redis 存储当前 ID 值
 * 2. 使用 Lua 脚本原子性地获取下一批 ID 范围
 * 3. 本地使用 AtomicLong 在预分配范围内生成 ID
 * 4. 当本地 ID 用完后，通过 Lua 脚本从 Redis 获取下一批次
 *
 * 特点：
 * - 使用 Lua 脚本保证原子性，避免并发问题
 * - 本地预分配，减少 Redis 访问频率
 * - 分布式友好，多实例共享全局 ID 序列
 */
public class RedisIdGenerator implements IdGenerator {

    private static final Logger logger = LoggerFactory.getLogger(RedisIdGenerator.class);

    /**
     * Redis 中存储 ID 的 key
     */
    private final String redisKey;

    /**
     * Vertx 实例
     */
    private final Vertx vertx;

    /**
     * Redis 客户端（由外部注入，共享 RedisFactory 的客户端）
     */
    private final Redis redis;

    /**
     * Redis 连接（lazy init，由 tryInit 时 connect）
     */
    private volatile RedisConnection connection;

    /**
     * 本地当前 ID（下一个要返回的 ID）
     */
    private final AtomicLong currentId;

    /**
     * 本地批次结束 ID（不包含）
     */
    private volatile long batchEnd;

    /**
     * 是否已初始化
     */
    private final AtomicBoolean initialized;

    /**
     * 预分配大小
     */
    private volatile long allocationSize;

    /**
     * 是否正在从 Redis 预分配中
     */
    private final AtomicBoolean prefetching;

    /**
     * 获取下一批 ID 的 Lua 脚本
     * 原子性地递增 Redis 中的 ID 值，并返回新的起始 ID
     * 参数：KEYS[1]=redisKey, ARGV[1]=allocationSize
     * 返回值：新的起始 ID（递增前的值）
     */
    private static final String GET_NEXT_BATCH_SCRIPT =
      """
        local key = KEYS[1]
        local allocationSize = tonumber(ARGV[1])
        local current = redis.call('GET', key)
        if current == false then
            current = 0
        else
            current = tonumber(current)
        end
        local newId = current + allocationSize
        redis.call('SET', key, newId)
        return current
      """;

    /**
     * 初始化 ID 的 Lua 脚本
     * 如果 key 不存在则设置初始值
     * 参数：KEYS[1]=redisKey, ARGV[1]=initialValue
     * 返回值：1 表示设置成功，0 表示已存在
     */
    private static final String TRY_INIT_SCRIPT = """
      local key = KEYS[1]
          local initialValue = tonumber(ARGV[1])
          local current = redis.call('GET', key)
          if current == false then
              redis.call('SET', key, initialValue)
              return 1
          end
      return 0
      """;

    /**
     * 构造函数
     *
     * @param vertx Vertx 实例
     * @param redis Redis 客户端（由 RedisFactory 提供）
     */
    public RedisIdGenerator(Vertx vertx, Redis redis) {
        this(vertx, redis,
            ConfigHolder.getString("idGenerator.redisKey", "seq:id:generator"));
        this.allocationSize = ConfigHolder.getInt("idGenerator.allocationSize", 5000);
    }

    /**
     * 构造函数
     *
     * @param vertx Vertx 实例
     * @param redis Redis 客户端（由 RedisFactory 提供）
     * @param redisKey Redis 中存储 ID 的 key
     */
    public RedisIdGenerator(Vertx vertx, Redis redis, String redisKey) {
        this.vertx = vertx;
        this.redis = redis;
        this.redisKey = redisKey;
        this.currentId = new AtomicLong(0);
        this.batchEnd = 0;
        this.initialized = new AtomicBoolean(false);
        this.prefetching = new AtomicBoolean(false);
        this.allocationSize = ConfigHolder.getInt("idGenerator.allocationSize", 5000);
    }

    @Override
    public Future<Boolean> tryInit(long value, long allocationSize) {
        Promise<Boolean> promise = Promise.promise();

        if (initialized.compareAndSet(false, true)) {
            this.allocationSize = allocationSize > 0 ? allocationSize : this.allocationSize;

            // 从注入的 redis 客户端获取连接
            redis.connect().onComplete(connectAr -> {
                if (connectAr.succeeded()) {
                    connection = connectAr.result();

                    // 使用 Lua 脚本初始化
                    Request req = Request.cmd(EVAL)
                            .arg(TRY_INIT_SCRIPT)
                            .arg("1")
                            .arg(redisKey)
                            .arg(String.valueOf(value));

                    connection.send(req).onComplete(ar -> {
                        if (ar.succeeded()) {
                            long initResult = toLong(ar.result());
                            if (initResult == 1) {
                                currentId.set(value);
                                batchEnd = value + allocationSize;
                                logger.info("RedisIdGenerator initialized: start={}, allocationSize={}", value, allocationSize);
                                promise.complete(true);
                            } else {
                                fetchCurrentId().onComplete(fetchAr -> {
                                    if (fetchAr.succeeded()) {
                                        currentId.set(fetchAr.result());
                                        batchEnd = currentId.get() + allocationSize;
                                        logger.info("RedisIdGenerator already initialized: start={}, allocationSize={}",
                                                currentId.get(), allocationSize);
                                        promise.complete(false);
                                    } else {
                                        promise.fail(fetchAr.cause());
                                    }
                                });
                            }
                        } else {
                            promise.fail(ar.cause());
                        }
                    });
                } else {
                    promise.fail(connectAr.cause());
                }
            });
        } else {
            promise.complete(false);
        }

        return promise.future();
    }

    @Override
    public Future<Long> nextId() {
        Promise<Long> promise = Promise.promise();

        if (!initialized.get()) {
            // 延迟初始化：首次调用 nextId 时自动 init
            tryInit(0, allocationSize).onComplete(initAr -> {
                if (initAr.succeeded()) {
                    doNextId(promise);
                } else {
                    promise.fail(initAr.cause());
                }
            });
            return promise.future();
        }

        doNextId(promise);
        return promise.future();
    }

    private void doNextId(Promise<Long> promise) {
        // 尝试从本地获取
        long nextId = currentId.getAndIncrement();

        if (nextId < batchEnd) {
            // 本地批次还有剩余
            promise.complete(nextId);
        } else {
            // 本地批次用完，需要从 Redis 获取下一批
            // 检查是否正在预分配
            if (prefetching.compareAndSet(false, true)) {
                allocateNextBatch().onComplete(ar -> {
                    prefetching.set(false);
                    if (ar.succeeded()) {
                        long newStart = ar.result();
                        promise.complete(newStart);
                    } else {
                        promise.fail(ar.cause());
                    }
                });
            } else {
                waitForNextBatch(promise);
            }
        }
    }

    /**
     * 从 Redis 获取下一批 ID
     * 返回新的起始 ID
     */
    private Future<Long> allocateNextBatch() {
        Promise<Long> promise = Promise.promise();

        Request req = Request.cmd(EVAL)
                .arg(GET_NEXT_BATCH_SCRIPT)
                .arg("1")
                .arg(redisKey)
                .arg(String.valueOf(allocationSize));

        connection.send(req).onComplete(ar -> {
            if (ar.succeeded()) {
                long newStart = toLong(ar.result());
                currentId.set(newStart + 1);
                batchEnd = newStart + allocationSize;
                logger.debug("Allocated next batch: [{}, {})", newStart, batchEnd);
                promise.complete(newStart);
            } else {
                promise.fail(ar.cause());
            }
        });

        return promise.future();
    }

    /**
     * 获取当前 Redis 中的 ID 值
     */
    private Future<Long> fetchCurrentId() {
        Promise<Long> promise = Promise.promise();

        Request req = Request.cmd(Command.GET).arg(redisKey);
        connection.send(req).onComplete(ar -> {
            if (ar.succeeded()) {
                promise.complete(toLong(ar.result()));
            } else {
                promise.fail(ar.cause());
            }
        });

        return promise.future();
    }

    /**
     * 等待下一批次可用
     */
    private void waitForNextBatch(Promise<Long> promise) {
        vertx.setPeriodic(10, timerId -> {
            long current = currentId.get();
            if (current < batchEnd) {
                vertx.cancelTimer(timerId);
                promise.complete(currentId.getAndIncrement());
            }
        });
    }

    /**
     * 将对象转换为 Long
     */
    private Long toLong(Object obj) {
        if (obj == null) {
            return 0L;
        }
        if (obj instanceof Long) {
            return (Long) obj;
        }
        if (obj instanceof Number) {
            return ((Number) obj).longValue();
        }
        if (obj instanceof String) {
            try {
                return Long.parseLong((String) obj);
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
        if (obj instanceof io.vertx.redis.client.Response) {
            return ((io.vertx.redis.client.Response) obj).toLong();
        }
        return 0L;
    }

    /**
     * 获取当前批次信息
     *
     * @return 批次信息
     */
    public String getBatchInfo() {
        return String.format("currentId=%d, batchEnd=%d, remaining=%d, allocationSize=%d",
                currentId.get(), batchEnd, batchEnd - currentId.get(), allocationSize);
    }

    /**
     * 关闭生成器，释放 Redis 连接
     */
    public void close() {
        if (connection != null) {
            connection.close();
        }
        logger.info("RedisIdGenerator closed");
    }
}
