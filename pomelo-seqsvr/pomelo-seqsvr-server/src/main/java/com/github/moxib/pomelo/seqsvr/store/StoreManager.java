package com.github.moxib.pomelo.seqsvr.store;

import com.github.moxib.pomelo.seqsvr.proto.RangeId;
import com.github.moxib.pomelo.seqsvr.proto.Router;
import com.github.moxib.pomelo.seqsvr.proto.RouterNode;
import com.github.moxib.pomelo.seqsvr.proto.SeqSvrConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 MappedByteBuffer 的持久化存储管理器。
 * mmap 文件 + 路由表 JSON 文件。
 * <p>
 * 文件格式：
 *   set_{idBegin}_{size}.db      — mmap 文件，每个 section 8 字节存储 max_seq
 *   router_{idBegin}_{size}.dat  — JSON 文件，存储路由表
 */
public class StoreManager {

  private static final Logger LOG = LoggerFactory.getLogger(StoreManager.class);

  private final RangeId setId;
  private final MappedByteBuffer sectionMaxSeqsBuf;
  private final int sectionCount;
  private final RandomAccessFile sectionFile;
  private final Path routeTablePath;
  private volatile Router cacheRouter;

  /**
   * 创建 StoreManager，对应 Go 的 MustNewStoreManager。
   *
   * @param setId   Set ID 范围
   * @param dataDir mmap 和路由表文件存储目录
   */
  public StoreManager(RangeId setId, String dataDir) throws IOException {
    this.setId = setId;
    this.sectionCount = setId.calcSetSectionSize();
    long sectionMemSize = (long) sectionCount << 3;  // sectionCount * 8

    // mmap 文件: set_{idBegin}_{size}.db
    String seqFileName = String.format("set_%d_%d.db", setId.getIdBegin(), setId.getSize());
    Path seqFilePath = Path.of(dataDir, seqFileName);
    boolean isFirst = !Files.exists(seqFilePath);

    sectionFile = new RandomAccessFile(seqFilePath.toFile(), "rw");
    if (isFirst) {
      sectionFile.setLength(sectionMemSize);
    } else {
      long existingSize = sectionFile.length();
      if (existingSize != sectionMemSize) {
        throw new IllegalStateException(
          String.format("section file size mismatch: expected %d, got %d", sectionMemSize, existingSize));
      }
    }

    sectionMaxSeqsBuf = sectionFile.getChannel().map(
      FileChannel.MapMode.READ_WRITE, 0, sectionMemSize);
    sectionMaxSeqsBuf.order(ByteOrder.LITTLE_ENDIAN);

    if (isFirst) {
      // 清零初始化
      for (int i = 0; i < sectionCount; i++) {
        sectionMaxSeqsBuf.putLong(i * 8, 0L);
      }
      // 首次创建必须 force，确保文件落盘
      sectionMaxSeqsBuf.force();
    }

    // 路由表文件: router_{idBegin}_{size}.dat
    String routeFileName = String.format("router_%d_%d.dat", setId.getIdBegin(), setId.getSize());
    this.routeTablePath = Path.of(dataDir, routeFileName);

    if (Files.exists(routeTablePath)) {
      String json = Files.readString(routeTablePath);
      if (!json.isBlank()) {
        this.cacheRouter = RouterJsonCodec.decode(json);
      }
    }

    if (this.cacheRouter == null) {
      this.cacheRouter = new Router(0, new ArrayList<>());
    }

    LOG.info("StoreManager initialized: set={}, sections={}, memSize={}bytes, dataDir={}",
      setId.getIdBegin(), sectionCount, sectionMemSize, dataDir);
  }

  // ==================== Section MaxSeq 读写 ====================

  /**
   * 设置 section max_seq
   * 只有当新值大于旧值时才更新，并且向上取整到 SEQ_STEP 边界。
   */
  public long setSectionMaxSeq(int id, long maxSeq) {
    RangeId.SectionResult result = setId.calcSectionID(id);
    if (!result.isFound()) {
      LOG.error("setSectionMaxSeq: id {} not in set {}", id, setId.getIdBegin());
      return 0;
    }

    int sectionIdx = result.getSectionIdx();
    long oldMaxSeq = sectionMaxSeqsBuf.getLong(sectionIdx * 8);

    long newMaxSeq = maxSeq;
    if (newMaxSeq > oldMaxSeq) {
      // 向上取整到 SEQ_STEP 边界
      newMaxSeq = ((maxSeq / SeqSvrConstants.SEQ_STEP) + 1) * SeqSvrConstants.SEQ_STEP;
      sectionMaxSeqsBuf.putLong(sectionIdx * 8, newMaxSeq);
      sectionMaxSeqsBuf.force();
    }

    return newMaxSeq;
  }

  /**
   * 获取所有 section 的 max_seq 数组
   */
  public long[] getMaxSeqsData() {
    long[] maxSeqs = new long[sectionCount];
    for (int i = 0; i < sectionCount; i++) {
      maxSeqs[i] = sectionMaxSeqsBuf.getLong(i * 8);
    }
    return maxSeqs;
  }

  // ==================== 路由表读写 ====================

  /**
   * 获取缓存的路由表
   */
  public Router getCacheRouter() {
    return cacheRouter;
  }

  /**
   * 保存路由表到文件并更新缓存
   */
  public void saveCacheRouter(Router router) throws IOException {
    String json = RouterJsonCodec.encode(router);
    Files.writeString(routeTablePath, json);
    this.cacheRouter = router;
    LOG.info("Route table saved: version={}, nodes={}", router.getVersion(), router.getNodeList().size());
  }

  // ==================== 生命周期 ====================

  public void close() throws IOException {
    sectionMaxSeqsBuf.force();
    if (sectionFile != null) {
      sectionFile.close();
    }
    LOG.info("StoreManager closed");
  }

  public RangeId getSetId() { return setId; }
  public int getSectionCount() { return sectionCount; }
}
