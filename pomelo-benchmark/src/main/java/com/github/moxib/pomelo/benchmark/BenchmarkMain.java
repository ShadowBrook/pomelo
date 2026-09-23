package com.github.moxib.pomelo.benchmark;

import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.proto.auth.AuthProto;
import com.github.moxib.pomelo.proto.chat.ChatProto;
import com.github.moxib.pomelo.proto.relation.RelationProto;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * </pre>
 * 公网形态（Caddy 边缘 TLS）：`--host <域名> --api-port 443 --tcp-port 9000 --tls`。
 * 服务器本机内网形态（绕开公网带宽）：`--host 127.0.0.1 --api-port 8888 --api-tls
 * --tcp-port 8999`——API 自签 HTTPS、网关明文，故只对 API 开 TLS。
 *
 * 好友/消息也可按人计量：`--friends-per-user K`（每人 K 个好友，K ≥ 用户数-1 时为全连接）
 * 与 `--messages-per-user M`（总量 = 用户数 × M）。两者与旧的 `--friends`（好友对数）/
 * `--messages`（消息总数）互斥，给了按人参数就走按人路径。</p>
 */
public class BenchmarkMain {

  /** 进度打印间隔（秒） */
  private static final int PROGRESS_INTERVAL_SECONDS = 15;

  /** 无进展判定阈值（秒）：超过这么久没有任何请求完成，视为卡死 */
  private static final int STALL_TIMEOUT_SECONDS = 120;

  public static void main(String[] args) throws Exception {
    Config cfg = Config.parse(args);
    System.out.println("配置: " + cfg);

    Vertx vertx = Vertx.vertx();
    try {
      // 阶段 1：批量注册用户
      UserRegistry registry = new UserRegistry(vertx, cfg.host, cfg.apiPort, cfg.apiTls);
      Metrics regMetrics = new Metrics("注册", cfg.users);
      List<UserRegistry.UserInfo> users = registry.register(cfg.users, cfg.concurrency, regMetrics)
        .toCompletionStage().toCompletableFuture().join();
      regMetrics.report();
      registry.close();

      // 阶段 2：连接 + 登录（登录失败的客户端为 null，后续跳过）
      ImClient[] clients = connectAndLogin(vertx, users, cfg);

      int f = Math.min(cfg.friends, users.size() / 2);
      if (cfg.friendsPerUser <= 0 && f < 1) {
        System.out.println("好友对数不足，跳过加好友和发消息阶段");
        return;
      }

      // 有效配对：两个用户都登录成功的才参与加好友/发消息，避免 null 客户端
      List<int[]> pairs = cfg.friendsPerUser > 0
        ? meshPairs(clients, cfg.friendsPerUser)
        : validPairs(clients, f);
      if (pairs.isEmpty()) {
        System.out.println("无有效登录配对，跳过加好友和发消息阶段");
        return;
      }
      System.out.printf("好友图: 用户=%d 好友对=%d 平均每人 %.1f 个好友%n",
        clients.length, pairs.size(), pairs.size() * 2.0 / clients.length);

      // 阶段 3：加好友（申请 + 接受）
      addFriends(clients, users, pairs, cfg);

      // 阶段 4：发单聊消息
      sendMessages(clients, users, pairs, cfg);

      for (ImClient c : clients) {
        if (c != null) c.close();
      }
    } finally {
      // 半开连接（对端消失但没发 FIN）会让 vertx.close() 一直等不到事件循环退出，
      // 压测结果已经打完，给它 30s 优雅关闭的机会，超时或完成后都直接退出 JVM
      try {
        vertx.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
      } catch (Exception e) {
        System.err.println("vertx.close 未能在 30s 内完成，强制退出: " + e.getMessage());
      }
      System.exit(0);
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

  /**
   * 按人构造好友关系图：环上取 forward 偏移 1..⌈K/2⌉（去重后每人恰好 K 个好友，K 为奇数时 K+1）。
   * 偏移受 用户数/2 截断，K ≥ 用户数-1 时按无序对去重后即全连接（每人 用户数-1 个好友）。
   * 登录失败的客户端不参与。
   */
  private static List<int[]> meshPairs(ImClient[] clients, int friendsPerUser) {
    int n = clients.length;
    int k = Math.min((friendsPerUser + 1) / 2, n / 2);
    List<int[]> pairs = new ArrayList<>();
    Set<Long> seen = new HashSet<>();
    for (int i = 0; i < n; i++) {
      for (int d = 1; d <= k; d++) {
        int j = (i + d) % n;
        int a = Math.min(i, j);
        int b = Math.max(i, j);
        if (clients[a] == null || clients[b] == null) continue;
        if (seen.add((long) a * n + b)) {
          pairs.add(new int[]{a, b});
        }
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
      ImClient.connect(vertx, cfg.host, cfg.tcpPort, cfg.tcpTls, cfg.platform, cfg.messageSize)
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
    List<int[]> tasks = cfg.messagesPerUser > 0
      ? perUserTasks(clients, pairs, cfg.messagesPerUser)
      : roundRobinTasks(pairs, cfg.messages);
    if (tasks.isEmpty()) {
      System.out.println("无可用发消息任务，跳过发消息阶段");
      return;
    }
    System.out.printf("发消息任务: total=%d (per user=%d)%n",
      tasks.size(), tasks.size() / Math.max(clients.length, 1));
    long inBefore = sumBytesIn(clients);
    long outBefore = sumBytesOut(clients);
    Metrics msgMetrics = new Metrics("发消息", tasks.size());
    runConcurrent(tasks.size(), cfg.concurrency, (i, start) -> {
      int[] t = tasks.get(i);
      int sender = t[0];
      int peer = t[1];
      return sendAndRecord(
        clients[sender].sendMessage(
          users.get(sender).userId(), users.get(sender).userName(), users.get(peer).userId(), "bench-" + i),
        msgMetrics, i, start, "发消息");
    });
    msgMetrics.report();
    reportTraffic(msgMetrics, tasks.size(), clients,
      inBefore, outBefore, sumBytesIn(clients), sumBytesOut(clients), cfg);
  }

  /**
   * 流量换算：下行（客户端收）≈ 服务端出带宽、上行（客户端发）≈ 服务端入带宽。
   * 明文帧不含传输层开销，按每帧 +60 B（TLS 记录 ≈21 B + IP/TCP ≈40 B）折算线上线速。
   */
  private static void reportTraffic(Metrics metrics, int total, ImClient[] clients,
                                    long inBefore, long outBefore, long inAfter, long outAfter, Config cfg) {
    long inBytes = inAfter - inBefore;
    long outBytes = outAfter - outBefore;
    long respBytes = sumBytesInResp(clients);
    long pushBytes = sumBytesInPush(clients);
    long pushFrames = sumPushFrames(clients);
    long respFrames = sumRespFrames(clients);
    long framesOut = sumFramesOut(clients);
    double elapsedSec = metrics.elapsedSeconds();
    long framesIn = respFrames + pushFrames;
    double downWireBytes = inBytes + 60.0 * framesIn;
    double upWireBytes = outBytes + 60.0 * framesOut;
    double downPerMsg = downWireBytes / total;
    double upPerMsg = upWireBytes / total;
    System.out.printf("流量(明文帧): 下行 %.1f B/条 = 响应 %.1f + 推送 %.1f（%d 帧/条）、上行 %.1f B/条%n",
      inBytes / (double) total, respBytes / (double) total, pushBytes / (double) total,
      Math.round(pushFrames / (double) total), outBytes / (double) total);
    System.out.printf("线速口径(每帧 +60 B): 出 %.2f Mbps / 入 %.2f Mbps，合计 %.0f B/条%n",
      downWireBytes * 8 / elapsedSec / 1e6, upWireBytes * 8 / elapsedSec / 1e6, downPerMsg + upPerMsg);
    if (cfg.bandwidthMbps > 0) {
      System.out.printf("带宽上限 %.1f Mbps ÷ 单条合计 %.0f B → 理论上限约 %.0f qps（纯出带宽口径另为 %.0f qps）%n",
        cfg.bandwidthMbps, downPerMsg + upPerMsg, cfg.bandwidthMbps * 1e6 / 8 / (downPerMsg + upPerMsg),
        cfg.bandwidthMbps * 1e6 / 8 / downPerMsg);
    }
  }

  private static long sumRespFrames(ImClient[] clients) {
    long sum = 0;
    for (ImClient c : clients) {
      if (c != null) sum += c.respFrames();
    }
    return sum;
  }

  private static long sumFramesOut(ImClient[] clients) {
    long sum = 0;
    for (ImClient c : clients) {
      if (c != null) sum += c.framesOut();
    }
    return sum;
  }

  private static long sumBytesInResp(ImClient[] clients) {
    long sum = 0;
    for (ImClient c : clients) {
      if (c != null) sum += c.bytesInResp();
    }
    return sum;
  }

  private static long sumBytesInPush(ImClient[] clients) {
    long sum = 0;
    for (ImClient c : clients) {
      if (c != null) sum += c.bytesInPush();
    }
    return sum;
  }

  private static long sumPushFrames(ImClient[] clients) {
    long sum = 0;
    for (ImClient c : clients) {
      if (c != null) sum += c.pushFrames();
    }
    return sum;
  }

  private static long sumBytesIn(ImClient[] clients) {
    long sum = 0;
    for (ImClient c : clients) {
      if (c != null) sum += c.bytesIn();
    }
    return sum;
  }

  private static long sumBytesOut(ImClient[] clients) {
    long sum = 0;
    for (ImClient c : clients) {
      if (c != null) sum += c.bytesOut();
    }
    return sum;
  }

  /**
   * 每人 messagesPerUser 条：在自己好友里轮转发给下一位（总条数 = 用户数 × 每人条数）。
   * 按“第 m 条”外层循环交错发放：并发窗口同时压在所有连接上，与真实用户各发各的形态一致；
   * 若按用户分组发放，后段用户的连接会长时间空转，被网关按 idleTimeout 回收（曾导致整轮卡死）。
   */
  private static List<int[]> perUserTasks(ImClient[] clients, List<int[]> pairs, int messagesPerUser) {
    int n = clients.length;
    List<List<Integer>> friends = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      friends.add(new ArrayList<>());
    }
    for (int[] p : pairs) {
      friends.get(p[0]).add(p[1]);
      friends.get(p[1]).add(p[0]);
    }
    List<int[]> tasks = new ArrayList<>();
    for (int m = 0; m < messagesPerUser; m++) {
      for (int u = 0; u < n; u++) {
        List<Integer> own = friends.get(u);
        if (clients[u] == null || own.isEmpty()) continue;
        tasks.add(new int[]{u, own.get(m % own.size())});
      }
    }
    return tasks;
  }

  /** 旧路径：第 i 条消息落在 pairs[i % 对数] 上，方向按轮次交替（总条数 = total） */
  private static List<int[]> roundRobinTasks(List<int[]> pairs, int total) {
    int valid = pairs.size();
    List<int[]> tasks = new ArrayList<>(total);
    for (int i = 0; i < total; i++) {
      int[] p = pairs.get(i % valid);
      boolean aToB = (i / valid) % 2 == 0;
      tasks.add(aToB ? new int[]{p[0], p[1]} : new int[]{p[1], p[0]});
    }
    return tasks;
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

  /**
   * 通用并发执行：维持固定并发度，每个完成回调触发下一个。
   * 不设整体时长上限（十万级消息要跑十几分钟），改为按进度存活判定：
   * 连续 STALL_TIMEOUT_SECONDS 秒没有任何请求完成才认为卡死并放弃等待。
   */
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

    long beginNanos = System.nanoTime();
    for (int i = 0; i < Math.min(concurrency, total); i++) fire[0].run();

    long lastDone = 0;
    long lastProgressNanos = beginNanos;
    long lastReportNanos = beginNanos;
    long lastReportDone = 0;
    while (latch.getCount() > 0) {
      if (latch.await(PROGRESS_INTERVAL_SECONDS, TimeUnit.SECONDS)) break;
      long done = total - latch.getCount();
      long now = System.nanoTime();
      if (done > lastDone) {
        lastDone = done;
        lastProgressNanos = now;
      } else if (now - lastProgressNanos > TimeUnit.SECONDS.toNanos(STALL_TIMEOUT_SECONDS)) {
        System.err.printf("操作卡死: %d/%d 未完成且 %d 秒无进展，放弃等待%n",
          total - done, total, STALL_TIMEOUT_SECONDS);
        break;
      }
      if (now - lastReportNanos >= TimeUnit.SECONDS.toNanos(PROGRESS_INTERVAL_SECONDS)) {
        double windowSec = (now - lastReportNanos) / 1e9;
        double elapsedSec = (now - beginNanos) / 1e9;
        double windowQps = (done - lastReportDone) / windowSec;
        // 利特尔法则反推窗口内平均延迟：并发槽位始终被占满，平均延迟 ≈ 并发数 / 吞吐
        double windowLatencyMs = windowQps > 0 ? concurrency * 1000.0 / windowQps : 0;
        System.out.printf("进度: %d/%d 已用 %.0fs 窗口 qps=%.0f 平均延迟≈%.0fms 累计 qps=%.0f%n",
          done, total, elapsedSec, windowQps, windowLatencyMs, done / elapsedSec);
        lastReportNanos = now;
        lastReportDone = done;
      }
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

  private record Config(String host, int apiPort, int tcpPort, boolean apiTls, boolean tcpTls,
                        int users, int friends, int messages, int concurrency,
                        int friendsPerUser, int messagesPerUser, double bandwidthMbps, String platform, int messageSize) {
    static Config parse(String[] args) {
      String host = "localhost";
      int apiPort = 8888;
      int tcpPort = 9000;
      boolean apiTls = false;
      boolean tcpTls = false;
      int users = 100;
      int friends = 50;
      int messages = 1000;
      int concurrency = 50;
      int friendsPerUser = 0;
      int messagesPerUser = 0;
      double bandwidthMbps = 0;
      String platform = "web";
      int messageSize = 0;
      for (int i = 0; i < args.length; i++) {
        switch (args[i]) {
          case "--host" -> host = args[++i];
          case "--api-port" -> apiPort = Integer.parseInt(args[++i]);
          case "--tcp-port" -> tcpPort = Integer.parseInt(args[++i]);
          case "--tls" -> {
            apiTls = true;
            tcpTls = true;
          }
          case "--api-tls" -> apiTls = true;
          case "--tcp-tls" -> tcpTls = true;
          case "--users" -> users = Integer.parseInt(args[++i]);
          case "--friends" -> friends = Integer.parseInt(args[++i]);
          case "--messages" -> messages = Integer.parseInt(args[++i]);
          case "--concurrency" -> concurrency = Integer.parseInt(args[++i]);
          case "--friends-per-user" -> friendsPerUser = Integer.parseInt(args[++i]);
          case "--messages-per-user" -> messagesPerUser = Integer.parseInt(args[++i]);
          case "--bandwidth-mbps" -> bandwidthMbps = Double.parseDouble(args[++i]);
          case "--platform" -> platform = args[++i];
          case "--message-size" -> messageSize = Integer.parseInt(args[++i]);
          default -> System.err.println("忽略未知参数: " + args[i]);
        }
      }
      return new Config(host, apiPort, tcpPort, apiTls, tcpTls, users, friends, messages, concurrency,
        friendsPerUser, messagesPerUser, bandwidthMbps, platform, messageSize);
    }
  }
}
