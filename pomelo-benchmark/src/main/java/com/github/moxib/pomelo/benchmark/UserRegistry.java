package com.github.moxib.pomelo.benchmark;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 批量注册用户 — 通过 HTTP API（ApiVerticle 的 /api/user/register）并发创建用户。
 */
public class UserRegistry {

  public record UserInfo(String userId, String token, String userName) {}

  private static final String PASSWORD = "bench123";

  private final WebClient client;

  /**
   * @param tls 服务端 api 开启 TLS 时必须为 true（自签名证书走 trustAll）
   */
  public UserRegistry(Vertx vertx, String host, int port, boolean tls) {
    this.client = WebClient.create(vertx, new WebClientOptions()
      .setDefaultHost(host).setDefaultPort(port)
      .setSsl(tls).setTrustAll(tls));
  }

  /**
   * 并发注册 count 个用户，返回 userId + token 列表。
   * 用户名带时间戳前缀，避免重复运行时用户名冲突。
   */
  public Future<List<UserInfo>> register(int count, int concurrency, Metrics metrics) {
    Promise<List<UserInfo>> promise = Promise.promise();
    UserInfo[] users = new UserInfo[count];
    AtomicInteger sent = new AtomicInteger();
    AtomicInteger completed = new AtomicInteger();
    String prefix = "bench_" + System.currentTimeMillis();

    Runnable[] fire = new Runnable[1];
    fire[0] = () -> {
      int idx = sent.getAndIncrement();
      if (idx >= count) {
        if (completed.get() >= count) {
          promise.complete(Arrays.asList(users));
        }
        return;
      }
      String userName = prefix + "_" + idx;
      long start = System.nanoTime();
      JsonObject body = new JsonObject()
        .put("userName", userName)
        .put("nickname", userName)
        .put("password", PASSWORD);

      client.post("/api/user/register")
        .sendJson(body)
        .onComplete(ar -> {
          if (ar.succeeded() && ar.result().statusCode() == 201) {
            JsonObject resp = ar.result().bodyAsJsonObject();
            users[idx] = new UserInfo(resp.getString("userId"), resp.getString("token"), userName);
            metrics.record(idx, start);
          } else {
            metrics.error();
            metrics.record(idx, start);
            String detail = ar.succeeded()
              ? ar.result().statusCode() + " " + ar.result().bodyAsString()
              : ar.cause().getMessage();
            System.err.println("注册失败 idx=" + idx + ": " + detail);
          }
          completed.incrementAndGet();
          fire[0].run();
        });
    };

    for (int i = 0; i < Math.min(concurrency, count); i++) {
      fire[0].run();
    }
    return promise.future();
  }

  public void close() {
    client.close();
  }
}
