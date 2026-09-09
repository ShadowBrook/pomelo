package com.github.moxib.pomelo.seqsvr;

import com.github.moxib.pomelo.config.ClusterHelper;
import com.github.moxib.pomelo.seqsvr.alloc.SeqAllocVerticle;
import io.vertx.core.Vertx;

/**
 * AllocSvr 独立进程入口。
 * <pre>
 *   mvn -pl pomelo-seqsvr/pomelo-seqsvr-server exec:java \
 *     -Dexec.mainClass=com.github.moxib.pomelo.seqsvr.SeqAllocMain \
 *     -Dvertx.cluster=true -Dseqsvr.nodeId=node-1
 * </pre>
 * 需要先启动 StoreSvr（pomelo-seqsvr-store 的 StoreMain）。
 */
public class SeqAllocMain {
  public static void main(String[] args) {
    Vertx vertx = ClusterHelper.createVertx();
    vertx.deployVerticle(new SeqAllocVerticle())
      .onSuccess(id -> System.out.println("Alloc-Server started: deploymentId=" + id + ", clustered=" + vertx.isClustered()))
      .onFailure(e -> {
        System.err.println("Alloc-Server startup failed: " + e);
        System.exit(1);
      });
  }
}
