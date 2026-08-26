package com.github.moxib.pomelo.seqsvr.mediate;

import com.github.moxib.pomelo.seqsvr.proto.RangeId;
import com.github.moxib.pomelo.seqsvr.proto.Router;
import com.github.moxib.pomelo.seqsvr.proto.RouterNode;
import com.github.moxib.pomelo.seqsvr.proto.SeqSvrConstants;
import com.github.moxib.pomelo.seqsvr.rpc.StoreAccessor;
import io.vertx.core.Future;
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

  /** 内存 StoreAccessor：仅记录最近保存的路由表 */
  private static final class MemStore implements StoreAccessor {
    volatile Router saved;

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
    Router r1 = manager.register(node("node-1"));
    Router r2 = manager.register(node("node-2"));

    assertTrue(r2.getVersion() > r1.getVersion(), "版本应自增");
    assertEquals(2, r2.getNodeList().size());
    assertEquals(EXPECTED_SECTIONS, countSections(r2), "应覆盖全部分区");
    assertFalse(rangesOverlap(r2), "号段不应重叠");
    // 路由表已持久化
    assertNotNull(store.saved);
    assertEquals(r2.getVersion(), store.saved.getVersion());
  }

  @Test
  @DisplayName("单节点注册：独占全部分区")
  void testSingleNodeOwnsAll() {
    Router r = manager.register(node("node-1"));
    assertEquals(1, r.getNodeList().size());
    assertEquals(EXPECTED_SECTIONS, countSections(r));
  }

  @Test
  @DisplayName("优雅下线：号段重排给剩余节点")
  void testUnregisterRebalances() {
    manager.register(node("node-1"));
    manager.register(node("node-2"));
    Router r = manager.unregister("node-1");

    assertEquals(1, r.getNodeList().size());
    assertEquals("node-2", r.getNodeList().get(0).getNodeId());
    assertEquals(EXPECTED_SECTIONS, countSections(r), "剩余节点应接管全部分区");
  }

  @Test
  @DisplayName("心跳超时：失联节点被移除并重排路由表")
  void testHeartbeatTimeoutMigration() throws Exception {
    // 心跳超时设 100ms，便于测试
    MediateManager fast = new MediateManager(store, new RangeId(0, MAX), 100);

    fast.register(node("node-1"));
    fast.register(node("node-2"));
    assertFalse(fast.heartbeat("ghost", Collections.emptyMap()), "未知节点心跳应被拒");

    // 超过心跳超时：两节点都失联
    Thread.sleep(200);
    // node-1 恢复心跳，保持存活
    assertTrue(fast.heartbeat("node-1", Collections.emptyMap()), "心跳应被接受");

    assertTrue(fast.checkTimeouts(System.currentTimeMillis()), "应有节点被移除");
    assertEquals(1, fast.getNodeCount(), "失联节点应被移除");
    assertEquals(1, fast.getRouter().getNodeList().size());
    assertEquals("node-1", fast.getRouter().getNodeList().get(0).getNodeId());
  }

  @Test
  @DisplayName("增量迁移：扩容只割存量节点尾部，不重排存量节点头部")
  void testIncrementalKeepsExistingNodeHead() {
    manager.register(node("node-1"));
    Router twoNodes = manager.register(node("node-2"));
    int node2Head = firstSectionOf(twoNodes, "node-2");

    Router r = manager.register(node("node-3"));

    assertEquals(node2Head, firstSectionOf(r, "node-2"),
      "node-2 头部号段不因扩容而移位（增量迁移不重排存量节点头部）");
    assertEquals(EXPECTED_SECTIONS, countSections(r));
    assertFalse(rangesOverlap(r));
  }
}
