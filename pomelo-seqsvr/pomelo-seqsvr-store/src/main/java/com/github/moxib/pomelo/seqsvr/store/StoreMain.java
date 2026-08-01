package com.github.moxib.pomelo.seqsvr.store;

import com.github.moxib.pomelo.config.ClusterHelper;
import io.vertx.core.Vertx;

/**
 * StoreSvr 独立进程入口。
 * <pre>
 *   mvn -pl pomelo-seqsvr/pomelo-seqsvr-store exec:java \
 *     -Dexec.mainClass=com.github.moxib.pomelo.seqsvr.store.StoreMain \
 *     -Dvertx.cluster=true -Dseqsvr.dataDir=data/seqsvr
 * </pre>
 */
public class StoreMain {
  public static void main(String[] args) {
    Vertx vertx = ClusterHelper.createVertx();
    vertx.deployVerticle(new StoreVerticle())
      .onSuccess(id -> System.out.println("Store-Server started: deploymentId=" + id + ", clustered=" + vertx.isClustered()))
      .onFailure(e -> {
        System.err.println("Store-Server startup failed: " + e);
        System.exit(1);
      });
  }
}
