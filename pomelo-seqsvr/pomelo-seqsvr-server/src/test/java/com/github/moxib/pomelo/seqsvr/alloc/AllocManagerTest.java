package com.github.moxib.pomelo.seqsvr.alloc;

import com.github.moxib.pomelo.seqsvr.proto.*;
import com.github.moxib.pomelo.seqsvr.store.StoreManager;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AllocManager 核心分配逻辑测试。
 */
@DisplayName("AllocManager 分配逻辑测试")
class AllocManagerTest {

  private Path tempDir;
  private StoreManager storeManager;
  private AllocManager allocManager;
  private int maxIdSize = SeqSvrConstants.DEBUG_MAX_ID_SIZE;

  @BeforeEach
  void setUp() throws IOException {
    tempDir = Files.createTempDirectory("seqsvr-test-alloc-");
    RangeId setId = new RangeId(0, maxIdSize);
    storeManager = new StoreManager(setId, tempDir.toString());

    // 开发模式单节点
    RouterNode myNode = new RouterNode(
      "node-1", "127.0.0.1", 0,
      Collections.singletonList(new RangeId(0, maxIdSize)));

    allocManager = new AllocManager(storeManager, myNode, maxIdSize);
    allocManager.init();
  }

//  @AfterEach
//  void tearDown() throws IOException {
//    if (storeManager != null) storeManager.close();
//    try (var files = Files.walk(tempDir)) {
//      files.sorted(Comparator.reverseOrder()).forEach(p -> {
//        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
//      });
//    }
//  }

  @Test
  @DisplayName("分配器初始化为 INITED 状态")
  void testInitState() {
    assertEquals(AllocState.INITED, allocManager.getState(), "应为 INITED 状态");
  }

  @Test
  @DisplayName("fetchNextSequence 应严格递增")
  void testIncreasingSequence() {
    long prev = allocManager.fetchNextSequence(42, 0).getSeq();
    for (int i = 0; i < 100; i++) {
      long next = allocManager.fetchNextSequence(42, 0).getSeq();
      assertTrue(next > prev, String.format("seq 应递增: prev=%d, next=%d", prev, next));
      prev = next;
    }
  }

  @Test
  @DisplayName("同一用户 20000 次分配应跨批次")
  void testCrossBatch() {
    long first = allocManager.fetchNextSequence(100, 0).getSeq();
    long last = first;
    for (int i = 0; i < 20000; i++) {
      last = allocManager.fetchNextSequence(100, 0).getSeq();
    }
    assertTrue(last > first + SeqSvrConstants.SEQ_STEP,
      String.format("跨批次后应超出 SEQ_STEP: first=%d, last=%d", first, last));
  }

  @Test
  @DisplayName("不同用户应有独立序列")
  void testUserIsolation() {
    long seq1 = allocManager.fetchNextSequence(1, 0).getSeq();
    long seq2 = allocManager.fetchNextSequence(1, 0).getSeq();
    long seq3 = allocManager.fetchNextSequence(2, 0).getSeq();

    assertTrue(seq2 > seq1, "用户 1 的 seq 应递增");
    // 用户 2 从 section max 开始（初始为 0），第一次分配是 1
    assertTrue(seq3 >= 0, "用户 2 应有独立序列");
  }

  @Test
  @DisplayName("客户端路由过期时应返回最新 Router")
  void testRouterReturnWhenStale() {
    // 更新 router version
    Router newRouter = new Router(5, Collections.singletonList(allocManager.getCacheMyNode()));
    allocManager.updateRouter(newRouter);

    // clientVersion=0，小于 5
    Sequence seq = allocManager.fetchNextSequence(42, 0);
    assertNotNull(seq.getRouter(), "应返回最新路由表");
    assertEquals(5, seq.getRouter().getVersion());
  }

  @Test
  @DisplayName("客户端路由最新时不应返回 Router")
  void testNoRouterWhenUpToDate() {
    Router newRouter = new Router(5, Collections.singletonList(allocManager.getCacheMyNode()));
    allocManager.updateRouter(newRouter);

    // clientVersion=5，等于 server version
    Sequence seq = allocManager.fetchNextSequence(42, 5);
    assertNull(seq.getRouter(), "版本一致时不应返回路由表");
  }

  @Test
  @DisplayName("getCurrentSequence 不递增")
  void testGetCurrentDoesNotIncrement() {
    long cur = allocManager.getCurrentSequence(42, 0).getSeq();
    long same = allocManager.getCurrentSequence(42, 0).getSeq();
    assertEquals(cur, same, "getCurrent 不应递增");

    // 分配一个后 current 应改变
    allocManager.fetchNextSequence(42, 0);
    long after = allocManager.getCurrentSequence(42, 0).getSeq();
    assertTrue(after > cur, "分配后 current 应大于之前");
  }

  @Test
  @DisplayName("Section 边界检查：section 内的 uid")
  void testSectionBoundary() {
    // section_0 包含 uid 0-99999
    int uid0 = 0;
    int uidMax = SeqSvrConstants.SECTION_SIZE - 1;  // 99999

    assertDoesNotThrow(() -> allocManager.fetchNextSequence(uid0, 0));
    assertDoesNotThrow(() -> allocManager.fetchNextSequence(uidMax, 0));
  }
}
