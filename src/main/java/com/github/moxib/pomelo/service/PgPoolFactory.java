package com.github.moxib.pomelo.service;

import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PostgreSQL 连接池工厂（单例）。
 * 整个应用共享同一个 Pool，避免重复配置。
 */
public final class PgPoolFactory {

  private static final Logger LOG = LoggerFactory.getLogger(PgPoolFactory.class);

  private static volatile Pool instance;

  private PgPoolFactory() {}

  /** 获取共享连接池，首次调用时创建 */
  public static Pool get(Vertx vertx) {
    if (instance == null) {
      synchronized (PgPoolFactory.class) {
        if (instance == null) {
          PgConnectOptions opts = new PgConnectOptions()
            .setHost("localhost")
            .setPort(5432)
            .setDatabase("pomelo_db")
            .setUser("pomelo")
            .setPassword("pomelo123");
          PoolOptions poolOptions = new PoolOptions()
            .setMaxSize(10).setMaxWaitQueueSize(50)
            .setConnectionTimeout(10000)
            .setIdleTimeout(60);
          instance = PgBuilder.pool().with(poolOptions).connectingTo(opts).using(vertx).build();
          LOG.info("PgPool 已创建: {}:{}/{}", opts.getHost(), opts.getPort(), opts.getDatabase());
        }
      }
    }
    return instance;
  }

  /** 关闭连接池（应用关闭时调用） */
  public static void close() {
    if (instance != null) {
      synchronized (PgPoolFactory.class) {
        if (instance != null) {
          instance.close().onSuccess(v -> LOG.info("PgPool 已关闭"));
          instance = null;
        }
      }
    }
  }
}
