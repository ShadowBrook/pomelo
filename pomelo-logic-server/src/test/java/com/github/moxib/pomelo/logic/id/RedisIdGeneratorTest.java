package com.github.moxib.pomelo.logic.id;

import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisOptions;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RedisIdGenerator 测试类（使用 Testcontainers Redis）
 */
@Testcontainers
@ExtendWith(VertxExtension.class)
class RedisIdGeneratorTest {

    /**
     * Redis 测试容器
     */
    @Container
    static GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withReuse(true);

    private static Vertx vertx;
    private RedisIdGenerator idGenerator;

    @BeforeAll
    static void setUpAll() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void tearDownAll() {
        vertx.close();
    }

    @BeforeEach
    void setUp() {
        // 使用容器的 Redis 连接信息
        String redisHost = redisContainer.getHost();
        Integer redisPort = redisContainer.getMappedPort(6379);
        String connectionString = String.format("redis://%s:%d", redisHost, redisPort);

        RedisOptions options = new RedisOptions()
                .setConnectionString(connectionString);
        Redis redis = Redis.createClient(vertx, options);

        idGenerator = new RedisIdGenerator(vertx, redis, "test:seq:id:generator");
    }

    @AfterEach
    void tearDown() {
        if (idGenerator != null) {
            idGenerator.close();
        }
    }

    @Test
    @DisplayName("测试初始化")
    void testInit(VertxTestContext testContext) {
        idGenerator.tryInit(1000, 100)
                .onComplete(ar -> {
                    if (ar.succeeded()) {
                        assertTrue(ar.result(), "初始化应该返回 true");
                        testContext.completeNow();
                    } else {
                        testContext.failNow(ar.cause());
                    }
                });
    }

    @Test
    @DisplayName("测试重复初始化")
    void testRepeatedInit(VertxTestContext testContext) {
        idGenerator.tryInit(1000, 100)
                .compose(first -> {
                    // 第二次初始化应该返回 false
                    return idGenerator.tryInit(2000, 200);
                })
                .onComplete(ar -> {
                    if (ar.succeeded()) {
                        assertFalse(ar.result(), "重复初始化应该返回 false");
                        testContext.completeNow();
                    } else {
                        testContext.failNow(ar.cause());
                    }
                });
    }

    @Test
    @DisplayName("测试生成单个 ID")
    void testNextId(VertxTestContext testContext) {
        idGenerator.tryInit(1000, 100)
                .compose(v -> idGenerator.nextId())
                .onComplete(ar -> {
                    if (ar.succeeded()) {
                        long id = ar.result();
                        assertTrue(id >= 1000, "ID 应该大于等于初始值");
                        System.out.println("生成的 ID: " + id);
                        testContext.completeNow();
                    } else {
                        testContext.failNow(ar.cause());
                    }
                });
    }

    @Test
    @DisplayName("测试生成多个 ID 的递增性")
    void testNextIdsIncreasing(VertxTestContext testContext) {
        idGenerator.tryInit(1000, 100)
                .onComplete(ar -> {
                    if (ar.failed()) {
                        testContext.failNow(ar.cause());
                        return;
                    }

                    AtomicInteger count = new AtomicInteger(0);
                    AtomicLong lastId = new AtomicLong(0);
                    AtomicBoolean failed = new AtomicBoolean(false);

                    // 生成 200 个 ID（超过一批次）
                    for (int i = 0; i < 200; i++) {
                        final int index = i;
                        idGenerator.nextId().onComplete(idAr -> {
                            if (idAr.failed()) {
                                if (failed.compareAndSet(false, true)) {
                                    testContext.failNow(idAr.cause());
                                }
                                return;
                            }

                            long id = idAr.result();

                            if (index > 0) {
                                if (id <= lastId.get()) {
                                    if (failed.compareAndSet(false, true)) {
                                        testContext.failNow("ID 不是递增的：" + lastId.get() + " -> " + id);
                                    }
                                    return;
                                }
                            }
                            lastId.set(id);

                            if (count.incrementAndGet() == 200 && !failed.get()) {
                                testContext.completeNow();
                            }
                        });
                    }
                });
    }

    @Test
    @DisplayName("测试并发生成 ID 的唯一性")
    void testConcurrency(VertxTestContext testContext) throws Exception {
        int threadCount = 10;
        int idsPerThread = 100;

        CountDownLatch initLatch = new CountDownLatch(1);
        AtomicBoolean initSuccess = new AtomicBoolean(false);

        idGenerator.tryInit(1000, 500)
                .onComplete(ar -> {
                    if (ar.succeeded()) {
                        initSuccess.set(true);
                    } else {
                        testContext.failNow(ar.cause());
                    }
                    initLatch.countDown();
                });

        initLatch.await(30, TimeUnit.SECONDS);

        if (!initSuccess.get()) {
            testContext.failNow("初始化失败");
            return;
        }

        Set<Long> allIds = new HashSet<>();
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                for (int j = 0; j < idsPerThread; j++) {
                    try {
                        CountDownLatch idLatch = new CountDownLatch(1);
                        idGenerator.nextId().onComplete(idAr -> {
                            if (idAr.succeeded()) {
                                synchronized (allIds) {
                                    long id = idAr.result();
                                    if (!allIds.add(id)) {
                                        System.err.println("重复 ID: " + id);
                                        failureCount.incrementAndGet();
                                    }
                                }
                            } else {
                                failureCount.incrementAndGet();
                            }
                            idLatch.countDown();
                        });
                        idLatch.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        failureCount.incrementAndGet();
                    }
                }
                latch.countDown();
            }).start();
        }

        latch.await(60, TimeUnit.SECONDS);

        if (failureCount.get() > 0) {
            testContext.failNow("有 " + failureCount.get() + " 次失败");
        } else {
            assertEquals(threadCount * idsPerThread, allIds.size(),
                    "应该生成 " + (threadCount * idsPerThread) + " 个唯一 ID");
            testContext.completeNow();
        }
    }

    @Test
    @DisplayName("测试跨批次 ID 生成")
    void testCrossBatch(VertxTestContext testContext) throws Exception {
        int allocationSize = 100;
        // 超过 5 个批次
        int totalIds = 500;

        CountDownLatch initLatch = new CountDownLatch(1);
        AtomicBoolean initSuccess = new AtomicBoolean(false);

        idGenerator.tryInit(1, allocationSize)
                .onComplete(ar -> {
                    if (ar.succeeded()) {
                        initSuccess.set(true);
                    } else {
                        testContext.failNow(ar.cause());
                    }
                    initLatch.countDown();
                });

        initLatch.await(30, TimeUnit.SECONDS);

        if (!initSuccess.get()) {
            testContext.failNow("初始化失败");
            return;
        }

        Long[] ids = new Long[totalIds];
        CountDownLatch latch = new CountDownLatch(totalIds);
        AtomicBoolean failed = new AtomicBoolean(false);

        for (int i = 0; i < totalIds; i++) {
            final int index = i;
            idGenerator.nextId().onComplete(idAr -> {
                if (idAr.succeeded()) {
                    ids[index] = idAr.result();
                } else {
                    if (failed.compareAndSet(false, true)) {
                        testContext.failNow(idAr.cause());
                    }
                }
                latch.countDown();
            });
        }

        latch.await(30, TimeUnit.SECONDS);

        if (failed.get()) {
            return;
        }

        // 验证递增性
        for (int i = 1; i < ids.length; i++) {
            if (ids[i] <= ids[i - 1]) {
                testContext.failNow("ID 不是递增的：[" + (i - 1) + "]" + ids[i - 1] + " -> [" + i + "]" + ids[i]);
                return;
            }
        }

        // 验证唯一性
        Set<Long> uniqueIds = new HashSet<>();
        for (Long id : ids) {
            uniqueIds.add(id);
        }
        assertEquals(totalIds, uniqueIds.size(), "所有 ID 应该唯一");

        testContext.completeNow();
    }

    @Test
    @DisplayName("测试获取批次信息")
    void testGetBatchInfo() {
        String info = idGenerator.getBatchInfo();
        assertNotNull(info);
        System.out.println("批次信息：" + info);
    }

    @Test
    @DisplayName("测试未初始化调用 nextId")
    void testNextIdWithoutInit(VertxTestContext testContext) {
        idGenerator.nextId()
                .onComplete(ar -> {
                    if (ar.failed()) {
                        assertTrue(ar.cause() instanceof IllegalStateException,
                                "应该抛出 IllegalStateException");
                        testContext.completeNow();
                    } else {
                        testContext.failNow("未初始化时应该失败");
                    }
                });
    }

    @Test
    @DisplayName("测试不同 allocationSize 的效果")
    void testDifferentAllocationSize(VertxTestContext testContext) throws Exception {
        // 使用小的 allocationSize 来测试批次切换
        CountDownLatch initLatch = new CountDownLatch(1);

        idGenerator.tryInit(1, 10)
                .onComplete(ar -> {
                    if (ar.succeeded()) {
                        initLatch.countDown();
                    } else {
                        testContext.failNow(ar.cause());
                    }
                });

        initLatch.await(10, TimeUnit.SECONDS);

        // 生成 50 个 ID，应该会有 5 次批次切换
        CountDownLatch latch = new CountDownLatch(50);
        AtomicLong maxId = new AtomicLong(0);
        Set<Long> allIds = new HashSet<>();

        for (int i = 0; i < 50; i++) {
            idGenerator.nextId().onComplete(ar -> {
                if (ar.succeeded()) {
                    long id = ar.result();
                    synchronized (allIds) {
                        allIds.add(id);
                        maxId.set(Math.max(maxId.get(), id));
                    }
                } else {
                    testContext.failNow(ar.cause());
                }
                latch.countDown();
            });
        }

        latch.await(30, TimeUnit.SECONDS);

        assertEquals(50, allIds.size(), "应该生成 50 个唯一 ID");
        assertTrue(maxId.get() >= 50, "最大 ID 应该至少为 50");
        System.out.println("最大 ID: " + maxId.get());
        System.out.println("批次信息：" + idGenerator.getBatchInfo());

        testContext.completeNow();
    }
}
