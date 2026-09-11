package com.github.moxib.pomelo.seqsvr.alloc;

import com.github.moxib.pomelo.seqsvr.proto.AllocState;
import com.github.moxib.pomelo.seqsvr.proto.RangeId;
import com.github.moxib.pomelo.seqsvr.proto.Router;
import com.github.moxib.pomelo.seqsvr.proto.RouterNode;
import com.github.moxib.pomelo.seqsvr.proto.SeqSvrConstants;
import com.github.moxib.pomelo.seqsvr.rpc.StoreAccessor;
import com.github.moxib.pomelo.seqsvr.store.LocalStoreAccessor;
import com.github.moxib.pomelo.seqsvr.store.StoreManager;
import io.vertx.core.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 租约机制与状态机测试：
 * <ul>
 *   <li>租约失效 → ERROR 停止发号，恢复后回到 INITED</li>
 *   <li>号段被收回 → 立即卸载（停止服务）</li>
 *   <li>新增号段 → pending 延迟生效（旧 AllocSvr 停止后才服务）</li>
 *   <li>saveMaxSeq 以 Store 对齐返回值回填</li>
 * </ul>
 */
@DisplayName("AllocManager 租约与状态机测试")
class AllocManagerLeaseTest {

  private static final int SECTION = SeqSvrConstants.SECTION_SIZE;

  private Path tempDir;
  private StoreManager storeManager;
  private FlakyStoreAccessor store;
  private AllocManager alloc;

  @BeforeEach
  void setUp() throws Exception {
    tempDir = Files.createTempDirectory("seqsvr-test-lease-");
    RangeId setId = new RangeId(0, SeqSvrConstants.DEBUG_MAX_ID_SIZE);
    storeManager = new StoreManager(setId, tempDir.toString());
    store = new FlakyStoreAccessor(new LocalStoreAccessor(storeManager));
    alloc = newAlloc(new RangeId(0, SeqSvrConstants.DEBUG_MAX_ID_SIZE));
  }

  @AfterEach
  void tearDown() throws IOException {
    if (storeManager != null) {
      storeManager.close();
    }
    if (tempDir != null) {
      try (var files = Files.walk(tempDir)) {
        files.sorted(Comparator.reverseOrder()).forEach(p -> {
          try { Files.deleteIfExists(p); } catch (IOException ignored) {}
        });
      }
    }
  }

  private AllocManager newAlloc(RangeId nodeRange) throws Exception {
    return newAlloc(nodeRange, AllocManager.LEASE_TIMEOUT_MS, AllocManager.SYNC_LEASE_TIMEOUT_MS);
  }

  private AllocManager newAlloc(RangeId nodeRange, long leaseTimeoutMs, long syncLeaseMs) throws Exception {
    RouterNode myNode = new RouterNode("node-1", "127.0.0.1", 0, Collections.singletonList(nodeRange));
    AllocManager m = new AllocManager(store, new RangeId(0, SeqSvrConstants.DEBUG_MAX_ID_SIZE), myNode,
      SeqSvrConstants.DEBUG_MAX_ID_SIZE, leaseTimeoutMs, false, syncLeaseMs);
    await(m.init());
    return m;
  }

  private static void await(Future<Void> future) throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Throwable> err = new AtomicReference<>();
    future.onComplete(ar -> {
      if (ar.failed()) err.set(ar.cause());
      latch.countDown();
    });
    if (!latch.await(10, TimeUnit.SECONDS)) {
      throw new AssertionError("future timed out");
    }
    if (err.get() != null) {
      throw new AssertionError("future failed", err.get());
    }
  }

  @Test
  @DisplayName("租约失效：心跳超时后状态置 ERROR，拒绝发号")
  void testLeaseExpiryStopsServing() {
    assertDoesNotThrow(() -> alloc.fetchNextSequence(999999, 0), "正常时应可发号");

    long now = System.currentTimeMillis();
    alloc.backdateLeaseForTest(now - AllocManager.LEASE_TIMEOUT_MS - 1000);
    alloc.checkLease(now);

    assertEquals(AllocState.ERROR, alloc.getState(), "租约失效后应为 ERROR");
    assertThrows(IllegalStateException.class, () -> alloc.fetchNextSequence(1, 0), "ERROR 状态应拒绝发号");
  }

  @Test
  @DisplayName("租约恢复：重新读到 StoreSvr 后重载 max_seq 回到 INITED")
  void testLeaseRecovers() throws Exception {
    long now = System.currentTimeMillis();
    alloc.backdateLeaseForTest(now - AllocManager.LEASE_TIMEOUT_MS - 1000);
    alloc.checkLease(now);
    assertEquals(AllocState.ERROR, alloc.getState());

    // 模拟 StoreSvr 恢复：syncLease 成功刷新心跳
    await(alloc.syncLease());
    alloc.checkLease(System.currentTimeMillis());
    assertEquals(AllocState.INITED, alloc.getState(), "租约恢复后应回到 INITED");

    long first = alloc.fetchNextSequence(1, 0).getSeq();
    assertTrue(first > 0, "恢复后应能发号");
  }

  @Test
  @DisplayName("StoreSvr 写入失败时发号仍继续，但恢复后不回退")
  void testWriteFailureThenRecovery() throws Exception {
    store.setFailWrites(true);
    long seq = alloc.fetchNextSequence(7, 0).getSeq();
    assertTrue(seq > 0, "写失败不应阻塞发号");

    store.setFailWrites(false);
    // 继续分配，跨批次提升后 Store 值应 >= 内存值
    for (int i = 0; i < SeqSvrConstants.SEQ_STEP + 10; i++) {
      alloc.fetchNextSequence(7, 0);
    }
    long storeMax = storeManager.getMaxSeqsData()[0];
    long memMax = alloc.getSectionMaxSeq(0);
    assertTrue(memMax >= storeMax, "内存 section max 不应低于 Store 持久化值: mem=" + memMax + ", store=" + storeMax);
  }

  @Test
  @DisplayName("号段被收回：立即卸载，停止服务该号段")
  void testSectionUnloadedImmediately() {
    // 缩小 node-1 到只覆盖 section 0
    RouterNode narrow = new RouterNode("node-1", "127.0.0.1", 0,
      Collections.singletonList(new RangeId(0, SECTION)));
    alloc.updateRouter(new Router(1, Collections.singletonList(narrow)));

    // section 0 仍可服务
    assertDoesNotThrow(() -> alloc.fetchNextSequence(5, 0));
    // section 1 已被卸载
    assertThrows(IllegalArgumentException.class, () -> alloc.fetchNextSequence(SECTION, 0));
    assertFalse(alloc.getActiveSections().contains(1), "section 1 应已从 active 移除");
  }

  @Test
  @DisplayName("新增号段：pending 延迟生效，旧节点停止后才服务")
  void testNewSectionPendingDelay() throws Exception {
    // 初始只覆盖 section 0
    AllocManager narrow = newAlloc(new RangeId(0, SECTION));

    // 路由表扩到覆盖 section 0 + 1
    RouterNode wide = new RouterNode("node-1", "127.0.0.1", 0,
      Collections.singletonList(new RangeId(0, 2 * SECTION)));
    long now = System.currentTimeMillis();
    narrow.updateRouter(new Router(2, Collections.singletonList(wide)));

    // section 1 处于 pending，未生效
    assertTrue(narrow.getPendingSections().containsKey(1), "section 1 应为 pending");
    assertFalse(narrow.getActiveSections().contains(1), "section 1 尚未激活");
    assertThrows(IllegalArgumentException.class, () -> narrow.fetchNextSequence(SECTION, 0),
      "pending 中不应服务该号段");

    // 超过 pending 激活延迟后激活。生产环境 4s sync 会持续刷新租约心跳，这里同步推进心跳，避免误触租约超时
    long activationTime = now + narrow.getPendingActivateDelayMs() + 1;
    narrow.backdateLeaseForTest(activationTime);
    narrow.checkLease(activationTime);
    assertFalse(narrow.getPendingSections().containsKey(1), "激活后不再 pending");
    assertTrue(narrow.getActiveSections().contains(1), "激活后应服务");
    long first = narrow.fetchNextSequence(SECTION, 0).getSeq();
    assertTrue(first > 0, "section 1 首个 seq 应为正数");
  }

  @Test
  @DisplayName("saveMaxSeq 以 Store 对齐返回值回填内存 section max")
  void testSaveMaxSeqAlignedBackfill() {
    // 首次分配触发批次提升：Store 对齐到 10000
    alloc.fetchNextSequence(100, 0);
    assertEquals(10000L, alloc.getSectionMaxSeq(0), "内存 section max 应为 Store 对齐值");
    assertEquals(10000L, storeManager.getMaxSeqsData()[0], "Store 持久化应为对齐值");
  }

  @Test
  @DisplayName("租约常量：停服阈值 15s，pending 激活延迟必须严格大于停服阈值")
  void testLeaseConstantsKeepMigrationInvariant() throws Exception {
    AllocManager m = newAlloc(new RangeId(0, SECTION));
    assertEquals(15000, AllocManager.LEASE_TIMEOUT_MS, "停服阈值应放宽到 15s");
    assertEquals(AllocManager.LEASE_TIMEOUT_MS + AllocManager.SYNC_LEASE_TIMEOUT_MS,
      m.getPendingActivateDelayMs(), "pending 激活延迟 = 停服阈值 + 一个同步周期");
    assertTrue(m.getPendingActivateDelayMs() > AllocManager.LEASE_TIMEOUT_MS,
      "不变量：新号段必须等旧 owner 停服上界过去后才能服务，否则迁移期双写同一 section");
  }

  @Test
  @DisplayName("6s 无成功 store 读仍服务，超过 15s 阈值才停服")
  void testLeaseThresholdDecoupledFromPendingActivation() {
    assertDoesNotThrow(() -> alloc.fetchNextSequence(3, 0), "正常时应可发号");

    long now = System.currentTimeMillis();
    // 6s 读停顿：旧实现（5s 阈值）已停服，新实现应仍在服务
    alloc.backdateLeaseForTest(now - 6000);
    alloc.checkLease(now);
    assertEquals(AllocState.INITED, alloc.getState(), "6s 读停顿不应触发停服（阈值 15s）");
    assertDoesNotThrow(() -> alloc.fetchNextSequence(4, 0), "6s 读停顿内应可继续发号");

    // 超过 15s 阈值：停服
    alloc.backdateLeaseForTest(now - AllocManager.LEASE_TIMEOUT_MS - 1000);
    alloc.checkLease(now);
    assertEquals(AllocState.ERROR, alloc.getState(), "超过 15s 阈值应停服");
    assertThrows(IllegalStateException.class, () -> alloc.fetchNextSequence(5, 0));
  }

  @Test
  @DisplayName("pending 激活延迟随停服阈值放大（放宽停服阈值不得缩短迁移保护窗口）")
  void testPendingActivationDelayScalesWithLeaseThreshold() throws Exception {
    AllocManager shortLease = newAlloc(new RangeId(0, SECTION), 1000, 200);
    AllocManager longLease = newAlloc(new RangeId(0, SECTION), 20000, 200);

    assertEquals(1200, shortLease.getPendingActivateDelayMs(), "短租约下 = 1000 + 200");
    assertEquals(20200, longLease.getPendingActivateDelayMs(), "长租约下 = 20000 + 200");
    assertTrue(shortLease.getPendingActivateDelayMs() > 1000, "短租约也必须大于停服阈值");
    assertTrue(longLease.getPendingActivateDelayMs() > 20000,
      "停服阈值放宽到 20s 时保护窗口必须同步放大到 20s 以上");
  }

  @Test
  @DisplayName("pending 号段在激活延迟内不服务，到期才激活（迁移保护窗口）")
  void testPendingActivationBoundary() throws Exception {
    AllocManager m = newAlloc(new RangeId(0, SECTION), 1000, 200);
    RouterNode wide = new RouterNode("node-1", "127.0.0.1", 0,
      Collections.singletonList(new RangeId(0, 2 * SECTION)));
    long now = System.currentTimeMillis();
    m.updateRouter(new Router(2, Collections.singletonList(wide)));
    assertTrue(m.getPendingSections().containsKey(1), "扩容后 section 1 应为 pending");

    // 差 1ms 到期：仍不得服务（此时旧 owner 可能还没停服）
    long beforeDue = now + m.getPendingActivateDelayMs() - 1;
    m.backdateLeaseForTest(beforeDue);
    m.checkLease(beforeDue);
    assertTrue(m.getPendingSections().containsKey(1), "未到激活延迟不应激活");
    assertThrows(IllegalArgumentException.class, () -> m.fetchNextSequence(SECTION, 0),
      "pending 中不应服务该号段");

    // 到期：激活并开始服务
    long due = now + m.getPendingActivateDelayMs();
    m.backdateLeaseForTest(due);
    m.checkLease(due);
    assertFalse(m.getPendingSections().containsKey(1), "到期后不应再 pending");
    assertTrue(m.getActiveSections().contains(1), "到期后应服务新号段");
    assertTrue(m.fetchNextSequence(SECTION, 0).getSeq() > 0, "激活后应能发号");
  }
}
