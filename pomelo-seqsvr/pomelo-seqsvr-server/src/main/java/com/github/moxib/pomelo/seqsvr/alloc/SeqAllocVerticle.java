package com.github.moxib.pomelo.seqsvr.alloc;

import com.github.moxib.pomelo.config.ConfigHolder;
import com.github.moxib.pomelo.seqsvr.proto.*;
import com.github.moxib.pomelo.seqsvr.store.StoreManager;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;

/**
 * AllocSvr Verticle — 序列号分配服务。
 * 通过 EventBus 对外提供 FetchNextSequence / GetCurrentSequence。
 *
 */
public class SeqAllocVerticle extends VerticleBase {

  private static final Logger LOG = LoggerFactory.getLogger(SeqAllocVerticle.class);

  private AllocManager allocManager;
  private StoreManager storeManager;

  @Override
  public Future<?> start() {
    try {
      // 配置
      int maxIdSize = ConfigHolder.getInt("seqsvr.maxIdSize", SeqSvrConstants.DEBUG_MAX_ID_SIZE);
      String dataDir = ConfigHolder.getString("seqsvr.dataDir", "data/seqsvr");
      int setIdBegin = ConfigHolder.getInt("seqsvr.setIdBegin", 0);
      int setIdSize = ConfigHolder.getInt("seqsvr.setIdSize", maxIdSize);

      // 文件存储目录
      java.nio.file.Files.createDirectories(java.nio.file.Path.of(dataDir));

      // StoreManager — mmap 持久化
      RangeId setId = new RangeId(setIdBegin, setIdSize);
      storeManager = new StoreManager(setId, dataDir);

      // 开发模式：单节点覆盖所有 Section
      RangeId fullRange = new RangeId(0, maxIdSize);
      RouterNode myNode = new RouterNode(
        "node-1", "127.0.0.1", 0,
        Collections.singletonList(fullRange));

      // AllocManager — 核心分配逻辑
      allocManager = new AllocManager(storeManager, myNode, maxIdSize);

      // 加载已有 max_seqs 数据
      allocManager.init();

      // 注册 EventBus consumer
      vertx.eventBus().consumer("seqsvr.alloc.fetchNext", msg -> {
        JsonObject body = (JsonObject) msg.body();
        int id = body.getInteger("id");
        int version = body.getInteger("version", 0);
        try {
          Sequence seq = allocManager.fetchNextSequence(id, version);
          msg.reply(buildResponse(seq));
        } catch (Exception e) {
          LOG.error("fetchNextSequence failed: id={}", id, e);
          msg.fail(500, e.getMessage());
        }
      });

      vertx.eventBus().consumer("seqsvr.alloc.getCurrent", msg -> {
        JsonObject body = (JsonObject) msg.body();
        int id = body.getInteger("id");
        int version = body.getInteger("version", 0);
        try {
          Sequence seq = allocManager.getCurrentSequence(id, version);
          msg.reply(buildResponse(seq));
        } catch (Exception e) {
          LOG.error("getCurrentSequence failed: id={}", id, e);
          msg.fail(500, e.getMessage());
        }
      });

      LOG.info("SeqAllocVerticle started: setId={}, maxIdSize={}, state={}",
        setIdBegin, maxIdSize, allocManager.getState());
      return Future.succeededFuture();
    } catch (IOException e) {
      LOG.error("Failed to start SeqAllocVerticle", e);
      return Future.failedFuture(e);
    }
  }

  /**
   * 构建分配响应。
   * 若客户端路由表过期，将完整 Router 嵌入响应（嵌入式路由表机制）。
   */
  private JsonObject buildResponse(Sequence seq) {
    JsonObject resp = new JsonObject().put("seq", seq.getSeq());
    if (seq.hasRouter()) {
      resp.put("routeVersion", seq.getRouter().getVersion());
      resp.put("router", JsonObject.mapFrom(seq.getRouter()));
    }
    return resp;
  }

  @Override
  public Future<?> stop() {
    if (storeManager != null) {
      try {
        storeManager.close();
      } catch (IOException e) {
        LOG.warn("Error closing StoreManager", e);
      }
    }
    LOG.info("SeqAllocVerticle stopped");
    return Future.succeededFuture();
  }
}
