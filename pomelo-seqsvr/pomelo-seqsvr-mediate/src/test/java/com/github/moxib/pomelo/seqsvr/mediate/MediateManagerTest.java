package com.github.moxib.pomelo.seqsvr.mediate;

import com.github.moxib.pomelo.seqsvr.proto.RangeId;
import com.github.moxib.pomelo.seqsvr.proto.Router;
import com.github.moxib.pomelo.seqsvr.proto.RouterNode;
import com.github.moxib.pomelo.seqsvr.proto.SeqSvrConstants;
import com.github.moxib.pomelo.seqsvr.rpc.StoreAccessor;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MediateManager 仲裁逻辑测试：注册 / 下线 / 心跳超时 → 路由表重排。
 */
@DisplayName("MediateManager 仲裁逻辑测试")
class MediateManagerTest {

  private static final int MAX = SeqSvrConstants.DEBUG_MAX_ID_SIZE;
  private static final int EXPECTED_SECTIONS = new RangeId(0, MAX).calcSetSectionSize();

  /** 内存 StoreAccessor：仅记录最近保存的路由表，可注入保存失败 */
  private static class MemStore implements StoreAccessor {
    volatile Router saved;
    volatile boolean failSave;

    @Override
    public Future<long[]> loadMaxSeqsData() { return Future.succeededFuture(new long[0]); }

    @Override
    public Future<Long> saveMaxSeq(int id, long maxSeq) { return Future.succeededFuture(maxSeq); }

    @Override
    public Future<Router> loadRouteTable() {
      return Future.succeededFuture(saved == null ? new Router(0, new ArrayList<>()) : saved);
    }

    @Override
    public Future<Void> saveRouteTable(Router router) {
      if (failSave) {
        return Future.failedFuture("store down");
      }
      this.saved = router;
      return Future.succeededFuture();
    }
  }

  private MemStore store;
  private MediateManager manager;

  @BeforeEach
  void setUp() {
    store = new MemStore();
    manager = new MediateManager(store, new RangeId(0, MAX));
  }

  private static RouterNode node(String id) {
    return new RouterNode(id, "127.0.0.1", 0,
      Collections.singletonList(new RangeId(0, MAX)));
  }

  private static int countSections(Router router) {
    int n = 0;
    for (RouterNode node : router.getNodeList()) {
      for (RangeId range : node.getSectionRanges()) {
        n += range.calcSetSectionSize();
      }
    }
    return n;
  }

  private static boolean rangesOverlap(Router router) {
    List<RangeId> all = new ArrayList<>();
    for (RouterNode node : router.getNodeList()) {
      for (RangeId range : node.getSectionRanges()) {
        for (RangeId other : all) {
          long a0 = range.getIdBegin();
          long a1 = (long) range.getIdBegin() + range.getSize();
          long b0 = other.getIdBegin();
          long b1 = (long) other.getIdBegin() + other.getSize();
          if (Math.max(a0, b0) < Math.min(a1, b1)) {
            return true;
          }
        }
        all.add(range);
      }
    }
    return false;
  }

  /** 返回持有指定 section 索引的节点 id，无则 null。 */
  private static String sectionOwner(Router router, int sectionIdx) {
    int id = sectionIdx * SeqSvrConstants.SECTION_SIZE;
    for (RouterNode node : router.getNodeList()) {
      for (RangeId range : node.getSectionRanges()) {
        if (range.calcSectionID(id).isFound()) {
          return node.getNodeId();
        }
      }
    }
    return null;
  }

  /** 返回节点首个号段对应的 section 索引（测试 setId 从 0 开始）。 */
  private static int firstSectionOf(Router router, String nodeId) {
    for (RouterNode node : router.getNodeList()) {
      if (node.getNodeId().equals(nodeId)) {
        return node.getSectionRanges().get(0).getIdBegin() / SeqSvrConstants.SECTION_SIZE;
      }
    }
    throw new AssertionError("node not found: " + nodeId);
  }

  @Test
  @DisplayName("注册两个节点：路由表 2 节点、不重叠、覆盖全部分区")
  void testRegisterTwoNodesBalanced() {
    Router r1 = manager.register(node("node-1")).result();
    Router r2 = manager.register(node("node-2")).result();

    assertTrue(r2.getVersion() > r1.getVersion(), "版本应自增");
    assertEquals(2, r2.getNodeList().size());
    assertEquals(EXPECTED_SECTIONS, countSections(r2), "应覆盖全部分区");
    assertFalse(rangesOverlap(r2), "号段不应重叠");
    // 路由表已持久化
    assertNotNull(store.saved);
    assertEquals(r2.getVersion(), store.saved.getVersion());
  }

  /**
   * 可控挂起的 Store：saveRouteTable 直到 {@link #releaseAll()} 才完成，
   * 用于构造"前一次注册落盘尚未完成时第二次注册到达"的并发窗口。
   */
  private static class LatchedStore extends MemStore {
    private final List<Promise<Void>> inFlight = new ArrayList<>();
    private final List<Integer> committedVersions = new ArrayList<>();

    @Override
    public Future<Void> saveRouteTable(Router router) {
      Promise<Void> pending = Promise.promise();
      inFlight.add(pending);
      return pending.future().map(v -> {
        saved = router;
        committedVersions.add(router.getVersion());
        return null;
      });
    }

    void releaseAll() {
      List<Promise<Void>> batch = new ArrayList<>(inFlight);
      inFlight.clear();
      for (Promise<Void> pending : batch) {
        pending.complete();
      }
    }

    int inFlightCount() {
      return inFlight.size();
    }
  }

  @Test
  @DisplayName("并发注册：版本号不得复用，且落盘顺序与版本号一致")
  void concurrentRegistrationsMustNotShareVersion() throws Exception {
    LatchedStore latched = new LatchedStore();
    MediateManager m = new MediateManager(latched, new RangeId(0, MAX));

    // 第一次注册的持久化挂起未完成时，第二次注册到达（真实并发注册窗口）
    Future<Router> first = m.register(node("node-1"));
    Future<Router> second = m.register(node("node-2"));
    assertEquals(1, latched.inFlightCount(),
      "持久化必须串行：同一时刻只允许一次落盘在途，否则版本可能乱序覆盖");

    long deadline = System.currentTimeMillis() + 5000;
    while ((!first.isComplete() || !second.isComplete()) && System.currentTimeMillis() < deadline) {
      latched.releaseAll();
      Thread.sleep(10);
    }
    assertTrue(first.isComplete() && second.isComplete(), "两次注册都应完成");

    // 同一版本号一旦对应两张不同路由表，节点会按"版本相同即跳过"忽略真正的新表，
    // 号段被两个节点同时服务（seq 重复）
    assertNotEquals(first.result().getVersion(), second.result().getVersion(),
      "并发注册不得生成同版本号的路由表");
    assertTrue(second.result().getVersion() > first.result().getVersion(), "版本应单调递增");
    assertEquals(List.of(first.result().getVersion(), second.result().getVersion()),
      latched.committedVersions, "落盘顺序必须与版本号顺序一致");
    assertEquals(second.result().getVersion(), m.getRouter().getVersion(),
      "内存路由表应为最后一次生成的路由表");
    assertEquals(2, m.getRouter().getNodeList().size());
    assertFalse(rangesOverlap(m.getRouter()), "并发注册后号段不应重叠");
  }

  @Test
  @DisplayName("单节点注册：独占全部分区")
  void testSingleNodeOwnsAll() {
    Router r = manager.register(node("node-1")).result();
    assertEquals(1, r.getNodeList().size());
    assertEquals(EXPECTED_SECTIONS, countSections(r));
  }

  @Test
  @DisplayName("优雅下线：号段重排给剩余节点")
  void testUnregisterRebalances() {
    manager.register(node("node-1"));
    manager.register(node("node-2"));
    Router r = manager.unregister("node-1").result();

    assertEquals(1, r.getNodeList().size());
    assertEquals("node-2", r.getNodeList().get(0).getNodeId());
    assertEquals(EXPECTED_SECTIONS, countSections(r), "剩余节点应接管全部分区");
  }

  @Test
  @DisplayName("心跳超时：失联节点被移除并重排路由表")
  void testHeartbeatTimeoutMigration() throws Exception {
    // 心跳超时设 100ms，便于测试
    MediateManager fast = new MediateManager(store, new RangeId(0, MAX), 100);

    fast.register(node("node-1")).result();
    fast.register(node("node-2")).result();
    assertFalse(fast.heartbeat("ghost", Collections.emptyMap()), "未知节点心跳应被拒");

    // 超过心跳超时：两节点都失联
    Thread.sleep(200);
    // node-1 恢复心跳，保持存活
    assertTrue(fast.heartbeat("node-1", Collections.emptyMap()), "心跳应被接受");

    assertTrue(fast.checkTimeouts(System.currentTimeMillis()).result(), "应有节点被移除");
    assertEquals(1, fast.getNodeCount(), "失联节点应被移除");
    assertEquals(1, fast.getRouter().getNodeList().size());
    assertEquals("node-1", fast.getRouter().getNodeList().get(0).getNodeId());
  }

  @Test
  @DisplayName("增量迁移：扩容只割存量节点尾部，不重排存量节点头部")
  void testIncrementalKeepsExistingNodeHead() {
    manager.register(node("node-1")).result();
    Router twoNodes = manager.register(node("node-2")).result();
    int node2Head = firstSectionOf(twoNodes, "node-2");

    Router r = manager.register(node("node-3")).result();

    assertEquals(node2Head, firstSectionOf(r, "node-2"),
      "node-2 头部号段不因扩容而移位（增量迁移不重排存量节点头部）");
    assertEquals(EXPECTED_SECTIONS, countSections(r));
    assertFalse(rangesOverlap(r));
  }

  @Test
  @DisplayName("全部节点失联移除后：路由版本必须继续递增，不得归零（客户端按版本单调采纳）")
  void testVersionMonotonicWhenAllNodesRemoved() {
    // 心跳超时 0ms：注册即视为失联，便于触发全节点移除
    MediateManager fast = new MediateManager(store, new RangeId(0, MAX), 0);
    Router r1 = fast.register(node("node-1")).result();
    Router r2 = fast.register(node("node-2")).result();
    int before = r2.getVersion();
    assertTrue(before > 0, "注册后版本应为正数");

    // 线上事故场景（2026-09-07）：宿主停顿导致心跳集体超时，全部节点被移除，
    // 空成员分支曾把版本重置为 0，导致持有旧版本的客户端永久拒绝新路由表
    assertTrue(fast.checkTimeouts(System.currentTimeMillis() + 1).result(), "全部失联节点应被移除");
    assertEquals(0, fast.getNodeCount());
    Router empty = fast.getRouter();
    assertEquals(before + 1, empty.getVersion(), "空成员路由版本必须继续递增（不得归零）");

    Router back = fast.register(node("node-1")).result();
    assertEquals(before + 2, back.getVersion(), "重新注册后版本继续递增");
  }

  @Test
  @DisplayName("Store 持久化失败：注册失败返回，内存路由保持旧版本（版本不外泄未持久化值）")
  void testPersistFailureKeepsRouterUntouched() {
    MemStore store = new MemStore();
    MediateManager m = new MediateManager(store, new RangeId(0, MAX));
    store.failSave = true;

    Future<Router> first = m.register(node("node-1"));
    assertTrue(first.failed(), "持久化失败时注册应失败");
    assertEquals(0, m.getRouter().getVersion(), "内存路由不得更新为未持久化的版本");

    // Store 恢复后重新注册成功，且返回的路由与落盘一致。
    // 版本号在生成时即自增（不因持久化失败回退）：跳号无害，但绝不复用同版本号
    store.failSave = false;
    Router ok = m.register(node("node-1")).result();
    assertTrue(ok.getVersion() > 0, "重试成功后版本应为正数");
    assertSame(ok, m.getRouter(), "成功后内存路由应为本轮发布的路由");
    assertEquals(ok.getVersion(), store.saved.getVersion(), "成功路径必须先落盘");
  }
}
