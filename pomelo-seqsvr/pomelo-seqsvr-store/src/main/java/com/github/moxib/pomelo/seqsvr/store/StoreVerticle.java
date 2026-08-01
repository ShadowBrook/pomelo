package com.github.moxib.pomelo.seqsvr.store;

import com.github.moxib.pomelo.seqsvr.proto.RangeId;
import com.github.moxib.pomelo.seqsvr.proto.Router;
import com.github.moxib.pomelo.seqsvr.rpc.SeqSvrAddresses;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * StoreSvr Verticle — 持久化存储层。
 * <p>
 * 通过 EventBus 暴露四个 RPC（对应 Go StoreManager）：
 * <ul>
 *   <li>{@code seqsvr.store.loadMaxSeqsData} — 返回本 Set 全部 section 的 max_seq</li>
 *   <li>{@code seqsvr.store.saveMaxSeq} — 持久化 section max_seq，返回对齐后的新值</li>
 *   <li>{@code seqsvr.store.loadRouteTable} — 返回路由表</li>
 *   <li>{@code seqsvr.store.saveRouteTable} — 保存路由表</li>
 * </ul>
 * <p>
 * 除共享地址外，还按 replicaId 注册副本专属地址（{@code seqsvr.store.<replicaId>.*}），
 * 供 NRW 多副本客户端逐副本读写（Phase 5）。
 */
public class StoreVerticle extends VerticleBase {

  private static final Logger LOG = LoggerFactory.getLogger(StoreVerticle.class);

  private final StoreConfig config;
  private StoreManager storeManager;

  public StoreVerticle() {
    this.config = StoreConfig.fromConfig();
  }

  public StoreVerticle(StoreConfig config) {
    this.config = config;
  }

  @Override
  public Future<?> start() {
    try {
      Files.createDirectories(Path.of(config.dataDir()));
      RangeId setId = new RangeId(config.setIdBegin(), config.setIdSize());
      storeManager = new StoreManager(setId, config.dataDir());

      // 共享地址（单副本客户端 / 兜底）
      registerConsumer(SeqSvrAddresses.STORE_LOAD_MAX_SEQS, this::onLoadMaxSeqs);
      registerConsumer(SeqSvrAddresses.STORE_SAVE_MAX_SEQ, this::onSaveMaxSeq);
      registerConsumer(SeqSvrAddresses.STORE_LOAD_ROUTE_TABLE, this::onLoadRouteTable);
      registerConsumer(SeqSvrAddresses.STORE_SAVE_ROUTE_TABLE, this::onSaveRouteTable);
      // 副本专属地址（NRW 多副本客户端）
      String replicaId = config.replicaId();
      registerConsumer(SeqSvrAddresses.storeReplicaAddress(replicaId, "loadMaxSeqsData"), this::onLoadMaxSeqs);
      registerConsumer(SeqSvrAddresses.storeReplicaAddress(replicaId, "saveMaxSeq"), this::onSaveMaxSeq);
      registerConsumer(SeqSvrAddresses.storeReplicaAddress(replicaId, "loadRouteTable"), this::onLoadRouteTable);
      registerConsumer(SeqSvrAddresses.storeReplicaAddress(replicaId, "saveRouteTable"), this::onSaveRouteTable);

      LOG.info("StoreVerticle started: replicaId={}, setId=[{},{}), sections={}, dataDir={}",
        config.replicaId(), config.setIdBegin(), config.setIdSize(), storeManager.getSectionCount(), config.dataDir());
      return Future.succeededFuture();
    } catch (IOException e) {
      LOG.error("Failed to start StoreVerticle", e);
      return Future.failedFuture(e);
    }
  }

  private void registerConsumer(String address, io.vertx.core.Handler<Message<Object>> handler) {
    vertx.eventBus().consumer(address, handler);
  }

  private void onLoadMaxSeqs(Message<Object> msg) {
    long[] maxSeqs = storeManager.getMaxSeqsData();
    JsonArray arr = new JsonArray();
    for (long v : maxSeqs) {
      arr.add(v);
    }
    msg.reply(new JsonObject()
      .put("setIdBegin", storeManager.getSetId().getIdBegin())
      .put("setIdSize", storeManager.getSetId().getSize())
      .put("maxSeqs", arr));
  }

  private void onSaveMaxSeq(Message<Object> msg) {
    JsonObject body = (JsonObject) msg.body();
    int id = body.getInteger("id");
    long maxSeq = body.getLong("maxSeq");
    long aligned = storeManager.setSectionMaxSeq(id, maxSeq);
    msg.reply(new JsonObject().put("v", aligned));
  }

  private void onLoadRouteTable(Message<Object> msg) {
    msg.reply(JsonObject.mapFrom(storeManager.getCacheRouter()));
  }

  private void onSaveRouteTable(Message<Object> msg) {
    Router router = ((JsonObject) msg.body()).mapTo(Router.class);
    try {
      storeManager.saveCacheRouter(router);
      msg.reply(new JsonObject().put("ok", true));
    } catch (IOException e) {
      LOG.error("saveRouteTable failed: version={}", router.getVersion(), e);
      msg.fail(500, e.getMessage());
    }
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
    LOG.info("StoreVerticle stopped: replicaId={}", config.replicaId());
    return Future.succeededFuture();
  }
}
