package com.github.moxib.pomelo.seqsvr.store;

import com.github.moxib.pomelo.seqsvr.proto.RangeId;
import com.github.moxib.pomelo.seqsvr.proto.SeqSvrConstants;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StoreManager mmap 持久化测试。
 */
@DisplayName("StoreManager mmap 持久化测试")
class StoreManagerTest {

  private Path tempDir;
  private StoreManager storeManager;

  @BeforeEach
  void setUp() throws IOException {
    tempDir = Files.createTempDirectory("seqsvr-test-");
    int setIdSize = SeqSvrConstants.DEBUG_MAX_ID_SIZE;
    RangeId setId = new RangeId(0, setIdSize);
    storeManager = new StoreManager(setId, tempDir.toString());
  }

  @AfterEach
  void tearDown() throws IOException {
    if (storeManager != null) {
      storeManager.close();
    }
    // 清理临时文件
    try (var files = Files.walk(tempDir)) {
      files.sorted(Comparator.reverseOrder()).forEach(p -> {
        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
      });
    }
  }

  @Test
  @DisplayName("初始 max_seq 应全为 0")
  void testInitialMaxSeqsAreZero() {
    long[] maxSeqs = storeManager.getMaxSeqsData();
    int expectedCount = new RangeId(0, SeqSvrConstants.DEBUG_MAX_ID_SIZE).calcSetSectionSize();

    assertEquals(expectedCount, maxSeqs.length, "section 数量应正确");
    for (long v : maxSeqs) {
      assertEquals(0L, v, "初始 max_seq 应为 0");
    }
  }

  @Test
  @DisplayName("setSectionMaxSeq 应向上取整到 SEQ_STEP 边界")
  void testSetSectionMaxSeqRoundsUp() {
    // section_0 的 id 范围: 0 ~ 99999
    int idInSection0 = 50000;

    long result = storeManager.setSectionMaxSeq(idInSection0, 500L);

    // 500L / 10000 = 0, (0+1)*10000 = 10000
    assertEquals(10000L, result, "应向上取整到 SEQ_STEP 边界");

    long[] maxSeqs = storeManager.getMaxSeqsData();
    assertEquals(10000L, maxSeqs[0], "section_0 的 max_seq 应为 10000");
  }

  @Test
  @DisplayName("setSectionMaxSeq 不应降低已有的 max_seq")
  void testSetSectionMaxSeqOnlyIncreases() {
    int id = 50000;

    long first = storeManager.setSectionMaxSeq(id, 20000L);
    // 20000/10000=2, (2+1)*10000=30000
    assertEquals(30000L, first, "应向上取整到 SEQ_STEP 边界");

    storeManager.setSectionMaxSeq(id, 5000L);  // 尝试降级

    long[] maxSeqs = storeManager.getMaxSeqsData();
    assertEquals(30000L, maxSeqs[0], "max_seq 不应被降低");
  }

  @Test
  @DisplayName("路由表应可读写")
  void testRouteTableReadWrite() throws IOException {
    var router = storeManager.getCacheRouter();
    assertEquals(0, router.getVersion());

    router.setVersion(42);
    storeManager.saveCacheRouter(router);

    // 重新加载
    var loaded = storeManager.getCacheRouter();
    assertEquals(42, loaded.getVersion());
  }

  @Test
  @DisplayName("Section 文件应被创建")
  void testSectionFileCreated() {
    Path dbFile = tempDir.resolve("set_0_" + SeqSvrConstants.DEBUG_MAX_ID_SIZE + ".db");
    assertTrue(Files.exists(dbFile), "mmap 文件应被创建");
  }
}
