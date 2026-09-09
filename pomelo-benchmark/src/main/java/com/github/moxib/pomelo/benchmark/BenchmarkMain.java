package com.github.moxib.pomelo.benchmark;

import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.proto.auth.AuthProto;
import com.github.moxib.pomelo.proto.chat.ChatProto;
import com.github.moxib.pomelo.proto.relation.RelationProto;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.*;

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

      // 阶段 2：连接 + 登录（登录失败的客户端为 null，后续跳过）
      ImClient[] clients = connectAndLogin(vertx, users, cfg);

      int f = Math.min(cfg.friends, users.size() / 2);
      if (f < 1) {
        System.out.println("好友对数不足，跳过加好友和发消息阶段");
        return;
      }

      // 有效配对：两个用户都登录成功的才参与加好友/发消息，避免 null 客户端
      List<int[]> pairs = validPairs(clients, f);
      if (pairs.isEmpty()) {
        System.out.println("无有效登录配对，跳过加好友和发消息阶段");
        return;
      }

      // 阶段 3：加好友（申请 + 接受）
      addFriends(clients, users, pairs, cfg);

      // 阶段 4：发单聊消息
      sendMessages(clients, users, pairs, cfg);

      for (ImClient c : clients) {
        if (c != null) c.close();
      }
    } finally {
      vertx.close();
    }
  }

  /** 只保留两个用户都登录成功的配对（原始 index 0↔1, 2↔3 …） */
  private static List<int[]> validPairs(ImClient[] clients, int f) {
    List<int[]> pairs = new ArrayList<>();
    for (int i = 0; i < f; i++) {
      int a = i * 2;
      int b = i * 2 + 1;
      if (a < clients.length && b < clients.length && clients[a] != null && clients[b] != null) {
        pairs.add(new int[]{a, b});
      }
    }
    return pairs;
  }

  private static ImClient[] connectAndLogin(Vertx vertx, List<UserRegistry.UserInfo> users, Config cfg)
      throws InterruptedException {
    int n = users.size();
    ImClient[] clients = new ImClient[n];
    AtomicInteger ok = new AtomicInteger();
    AtomicInteger idx = new AtomicInteger();
    CountDownLatch latch = new CountDownLatch(n);
    long startNanos = System.nanoTime();

    Runnable[] fire = new Runnable[1];
    fire[0] = () -> {
      int i = idx.getAndIncrement();
      if (i >= n) return;
      UserRegistry.UserInfo u = users.get(i);
      ImClient.connect(vertx, cfg.host, cfg.tcpPort, cfg.tls)
        .onSuccess(c -> System.out.println("[login] 连接成功 i=" + i))
        .onFailure(e -> System.err.println("[login] 连接失败 i=" + i + ": " + e.getMessage()))
        .compose(c -> c.login(u.token(), u.userId(), u.userName()).map(r -> {
          clients[i] = c;
          ok.incrementAndGet();
          return c;
        }))
        .onComplete(ar -> {
          if (ar.failed()) {
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
    long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
    int okCount = ok.get();
    System.out.printf("登录: 成功=%d/%d 耗时=%dms qps=%.0f%n",
      okCount, n, elapsedMs, okCount > 0 ? okCount * 1000.0 / Math.max(elapsedMs, 1) : 0);
    return clients;
  }

  private static void addFriends(ImClient[] clients, List<UserRegistry.UserInfo> users,
                                 List<int[]> pairs, Config cfg) throws InterruptedException {
    int valid = pairs.size();
    Metrics addMetrics = new Metrics("加好友申请", valid);
    Metrics acceptMetrics = new Metrics("接受好友", valid);

    // 3a：批量申请（配对第 0 个用户申请第 1 个）
    runConcurrent(valid, cfg.concurrency, (i, start) -> {
      int[] p = pairs.get(i);
      return sendAndRecord(
        clients[p[0]].addFriend(users.get(p[0]).userId(), users.get(p[1]).userId()),
        addMetrics, i, start, "申请好友");
    });

    // 3b：批量接受（配对第 1 个用户接受第 0 个）
    runConcurrent(valid, cfg.concurrency, (i, start) -> {
      int[] p = pairs.get(i);
      return sendAndRecord(
        clients[p[1]].acceptFriend(users.get(p[1]).userId(), users.get(p[0]).userId()),
        acceptMetrics, i, start, "接受好友");
    });

    addMetrics.report();
    acceptMetrics.report();
  }

  private static void sendMessages(ImClient[] clients, List<UserRegistry.UserInfo> users,
                                   List<int[]> pairs, Config cfg) throws InterruptedException {
    Metrics msgMetrics = new Metrics("发消息", cfg.messages);
    int valid = pairs.size();
    runConcurrent(cfg.messages, cfg.concurrency, (i, start) -> {
      int[] p = pairs.get(i % valid);
      boolean aToB = (i / valid) % 2 == 0;
      int sender = aToB ? p[0] : p[1];
      int peer = aToB ? p[1] : p[0];
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
      byte[] body = resp.getBody();
      return switch (resp.getCmd()) {
        case CMD_AUTH_RESP_VALUE -> AuthProto.AuthResp.parseFrom(body).getCode();
        case CMD_FRIEND_ADD_RESP_VALUE -> RelationProto.FriendAddResp.parseFrom(body).getCode();
        case CMD_FRIEND_ACCEPT_RESP_VALUE -> RelationProto.FriendAcceptResp.parseFrom(body).getCode();
        case CMD_C2C_RESP_VALUE -> ChatProto.C2CResp.parseFrom(body).getCode();
        default -> -1;
      };
    } catch (Exception e) {
      return -1;
    }
  }

  @FunctionalInterface
  private interface Op {
    Future<Void> run(int idx, long startNanos);
  }

  private record Config(String host, int apiPort, int tcpPort, boolean tls,
                        int users, int friends, int messages, int concurrency) {
    static Config parse(String[] args) {
      String host = "localhost";
      int apiPort = 8888;
      int tcpPort = 9000;
      boolean tls = false;
      int users = 100;
      int friends = 50;
      int messages = 1000;
      int concurrency = 50;
      for (int i = 0; i < args.length; i++) {
        switch (args[i]) {
          case "--host" -> host = args[++i];
          case "--api-port" -> apiPort = Integer.parseInt(args[++i]);
          case "--tcp-port" -> tcpPort = Integer.parseInt(args[++i]);
          case "--tls" -> tls = true;
          case "--users" -> users = Integer.parseInt(args[++i]);
          case "--friends" -> friends = Integer.parseInt(args[++i]);
          case "--messages" -> messages = Integer.parseInt(args[++i]);
          case "--concurrency" -> concurrency = Integer.parseInt(args[++i]);
          default -> System.err.println("忽略未知参数: " + args[i]);
        }
      }
      return new Config(host, apiPort, tcpPort, tls, users, friends, messages, concurrency);
    }
  }
}
