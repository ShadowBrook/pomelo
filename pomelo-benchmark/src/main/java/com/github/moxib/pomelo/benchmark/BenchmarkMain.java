package com.github.moxib.pomelo.benchmark;

import com.github.moxib.pomelo.common.ImMessage;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 压力测试主入口 — 编排三阶段：注册用户 → 加好友（申请+接受）→ 发单聊消息。
 *
 * <p>用法：
 * <pre>
 * ./mvnw -pl pomelo-benchmark -am compile exec:java \
 *   -Dexec.args="--host localhost --api-port 8888 --tcp-port 9000 \
 *                --users 100 --friends 50 --messages 1000 --concurrency 50"
 * </pre></p>
 */
public class BenchmarkMain {

  public static void main(String[] args) throws Exception {
    Config cfg = Config.parse(args);
    System.out.println("配置: " + cfg);

    Vertx vertx = Vertx.vertx();
    try {
      // 阶段 1：批量注册用户
      UserRegistry registry = new UserRegistry(vertx, cfg.host, cfg.apiPort);
      Metrics regMetrics = new Metrics("注册", cfg.users);
      List<UserRegistry.UserInfo> users = registry.register(cfg.users, cfg.concurrency, regMetrics)
        .toCompletionStage().toCompletableFuture().join();
      regMetrics.report();
      registry.close();

      // 阶段 2：连接 + 登录
      ImClient[] clients = connectAndLogin(vertx, users, cfg);

      int f = Math.min(cfg.friends, users.size() / 2);
      if (f < 1) {
        System.out.println("好友对数不足，跳过加好友和发消息阶段");
        return;
      }

      // 阶段 3：加好友（申请 + 接受）
      addFriends(clients, users, f, cfg);

      // 阶段 4：发单聊消息
      sendMessages(clients, users, f, cfg);

      for (ImClient c : clients) {
        if (c != null) c.close();
      }
    } finally {
      vertx.close();
    }
  }

  private static ImClient[] connectAndLogin(Vertx vertx, List<UserRegistry.UserInfo> users, Config cfg)
      throws InterruptedException {
    int n = users.size();
    ImClient[] clients = new ImClient[n];
    Metrics loginMetrics = new Metrics("登录", n);
    CountDownLatch latch = new CountDownLatch(n);
    AtomicInteger idx = new AtomicInteger();

    Runnable[] fire = new Runnable[1];
    fire[0] = () -> {
      int i = idx.getAndIncrement();
      if (i >= n) return;
      UserRegistry.UserInfo u = users.get(i);
      long start = System.nanoTime();
      ImClient.connect(vertx, cfg.host, cfg.tcpPort)
        .compose(c -> c.login(u.token(), u.userId(), u.userName()).map(r -> {
          clients[i] = c;
          return c;
        }))
        .onComplete(ar -> {
          if (ar.succeeded()) {
            loginMetrics.record(i, start);
          } else {
            loginMetrics.error();
            loginMetrics.record(i, start);
            System.err.println("登录失败 " + u.userName() + ": " + ar.cause().getMessage());
          }
          latch.countDown();
          fire[0].run();
        });
    };

    for (int i = 0; i < Math.min(cfg.concurrency, n); i++) fire[0].run();
    if (!latch.await(120, TimeUnit.SECONDS)) {
      System.err.println("登录超时");
    }
    loginMetrics.report();
    return clients;
  }

  private static void addFriends(ImClient[] clients, List<UserRegistry.UserInfo> users, int f, Config cfg)
      throws InterruptedException {
    Metrics addMetrics = new Metrics("加好友申请", f);
    Metrics acceptMetrics = new Metrics("接受好友", f);

    // 3a：批量申请（用户 i 申请用户 i+1）
    runConcurrent(f, cfg.concurrency, (i, start) -> {
      int a = i * 2;
      int b = i * 2 + 1;
      return sendAndRecord(
        clients[a].addFriend(users.get(a).userId(), users.get(b).userId()),
        addMetrics, i, start, "申请好友");
    });

    // 3b：批量接受（用户 i+1 接受用户 i）
    runConcurrent(f, cfg.concurrency, (i, start) -> {
      int a = i * 2;
      int b = i * 2 + 1;
      return sendAndRecord(
        clients[b].acceptFriend(users.get(b).userId(), users.get(a).userId()),
        acceptMetrics, i, start, "接受好友");
    });

    addMetrics.report();
    acceptMetrics.report();
  }

  private static void sendMessages(ImClient[] clients, List<UserRegistry.UserInfo> users, int f, Config cfg)
      throws InterruptedException {
    Metrics msgMetrics = new Metrics("发消息", cfg.messages);
    runConcurrent(cfg.messages, cfg.concurrency, (i, start) -> {
      int pair = i % f;
      boolean aToB = (i / f) % 2 == 0;
      int sender = aToB ? pair * 2 : pair * 2 + 1;
      int peer = aToB ? pair * 2 + 1 : pair * 2;
      return sendAndRecord(
        clients[sender].sendMessage(
          users.get(sender).userId(), users.get(sender).userName(), users.get(peer).userId(), "bench-" + i),
        msgMetrics, i, start, "发消息");
    });
    msgMetrics.report();
  }

  /** 发送请求并记录延迟/成功率，返回 Future&lt;Void&gt; 供并发编排。 */
  private static Future<Void> sendAndRecord(Future<ImMessage> send, Metrics metrics, int idx,
                                            long start, String opName) {
    return send
      .onComplete(ar -> {
        if (ar.succeeded() && isSuccess(ar.result())) {
          metrics.record(idx, start);
        } else {
          metrics.error();
          metrics.record(idx, start);
          String detail = ar.succeeded() ? "code=" + respCode(ar.result()) : ar.cause().getMessage();
          System.err.println(opName + " 失败 idx=" + idx + ": " + detail);
        }
      })
      .mapEmpty();
  }

  /** 通用并发执行：维持固定并发度，每个完成回调触发下一个。 */
  private static void runConcurrent(int total, int concurrency, Op op) throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(total);
    AtomicInteger idx = new AtomicInteger();

    Runnable[] fire = new Runnable[1];
    fire[0] = () -> {
      int i = idx.getAndIncrement();
      if (i >= total) return;
      long start = System.nanoTime();
      op.run(i, start).onComplete(ar -> {
        latch.countDown();
        fire[0].run();
      });
    };

    for (int i = 0; i < Math.min(concurrency, total); i++) fire[0].run();
    if (!latch.await(300, TimeUnit.SECONDS)) {
      System.err.println("操作超时");
    }
  }

  private static boolean isSuccess(ImMessage resp) {
    return respCode(resp) == 0;
  }

  private static int respCode(ImMessage resp) {
    try {
      JsonObject body = new JsonObject(new String(resp.getBody(), StandardCharsets.UTF_8));
      return body.getInteger("code", -1);
    } catch (Exception e) {
      return -1;
    }
  }

  @FunctionalInterface
  private interface Op {
    Future<Void> run(int idx, long startNanos);
  }

  private record Config(String host, int apiPort, int tcpPort,
                        int users, int friends, int messages, int concurrency) {
    static Config parse(String[] args) {
      String host = "localhost";
      int apiPort = 8888;
      int tcpPort = 9000;
      int users = 100;
      int friends = 50;
      int messages = 1000;
      int concurrency = 50;
      for (int i = 0; i < args.length; i++) {
        switch (args[i]) {
          case "--host" -> host = args[++i];
          case "--api-port" -> apiPort = Integer.parseInt(args[++i]);
          case "--tcp-port" -> tcpPort = Integer.parseInt(args[++i]);
          case "--users" -> users = Integer.parseInt(args[++i]);
          case "--friends" -> friends = Integer.parseInt(args[++i]);
          case "--messages" -> messages = Integer.parseInt(args[++i]);
          case "--concurrency" -> concurrency = Integer.parseInt(args[++i]);
          default -> System.err.println("忽略未知参数: " + args[i]);
        }
      }
      return new Config(host, apiPort, tcpPort, users, friends, messages, concurrency);
    }
  }
}
