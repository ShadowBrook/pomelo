package com.github.moxib.pomelo.seqsvr.mediate;

import com.github.moxib.pomelo.config.ClusterHelper;
import io.vertx.core.Vertx;

/**
 * MediateSvr 独立进程入口。
 * <pre>
 *   mvn -pl pomelo-seqsvr/pomelo-seqsvr-mediate exec:java \
 *     -Dexec.mainClass=com.github.moxib.pomelo.seqsvr.mediate.MediateMain \
 *     -Dvertx.cluster=true
 * </pre>
 * 需要先启动 StoreSvr。
 */
public class MediateMain {
  public static void main(String[] args) {
    Vertx vertx = ClusterHelper.createVertx();
    vertx.deployVerticle(new MediateVerticle())
      .onSuccess(id -> System.out.println("Mediate-Server started: deploymentId=" + id + ", clustered=" + vertx.isClustered()))
      .onFailure(e -> {
        System.err.println("Mediate-Server startup failed: " + e);
        System.exit(1);
      });
  }
}
