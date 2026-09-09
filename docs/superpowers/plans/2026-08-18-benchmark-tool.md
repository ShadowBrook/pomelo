# 压力测试工具（pomelo-benchmark）实现规划

## Context

Pomelo 目前没有针对 IM 核心业务（用户注册、加好友、单聊消息）的压力测试工具。需要构建一个能模拟批量操作的压测工具，用来度量整条链路的吞吐（QPS）和延迟（P50/P99/max），为后续性能优化提供基线。

**决策：**
- **路径**：完整链路（HTTP 注册用户 + TCP IM 协议登录/加好友/发消息），走真实网关，覆盖网关序列化 + logic server + DB + 推送
- **加好友语义**：申请 + 接受（建立完整双向好友关系）
- **形态**：独立 Java 模块 `pomelo-benchmark`，`main` 入口 + 命令行参数（非 JUnit）

## 关键复用（不重复造轮子）

| 依赖 | 用途 |
|------|------|
| `pomelo-common` 的 `ImMessage.encodeToWire()/readFromWire()` | 二进制序列化（已含 4 字节长度前缀，直接 `socket.write()`） |
| `ImMessage.builder()` | 构建请求消息（cmd/codecId/messageId/body/varHeaders） |
| `CommonProto.Cmd.*_VALUE` 常量 | cmd 值 |
| `io.vertx.core.parsetools.RecordParser` | TCP 粘包处理（与 `TcpGatewayVerticle` 完全对称） |
| `io.vertx.ext.web.client.WebClient` | HTTP 批量注册（`vertx-web-client` 依赖） |
| `TcpGatewayVerticle.getTcpHandler()` | 粘包处理的参考实现（`pomelo-gateway`） |

**消息体结构**（对齐前端 `pomelo-web/src/sdk/client.ts`，JSON codec `codecId=1`）：

| 操作 | cmd | varHeaders | body（JSON） |
|------|-----|-----------|--------------|
| 登录 | `CMD_AUTH_REQ` 0x0001 | `{userId}` | `{token, userId, userName, deviceId, platform, appVersion}` |
| 加好友 | `CMD_FRIEND_ADD_REQ` 0x0062 | `{userId}` | `{userId, friendId}` |
| 接受好友 | `CMD_FRIEND_ACCEPT_REQ` 0x0065 | `{userId}` | `{userId, friendId}` |
| 发消息 | `CMD_C2C_REQ` 0x0010 | `{userId, userName, nickname}` | `{senderId, recipientId, message:{msgType, content}}` |
| 注册 | HTTP `POST /api/user/register` | — | `{userName, nickname, password}` → 响应 `{userId, userName, token}` |

## 模块结构

```
pomelo-benchmark/
  pom.xml                     # 依赖 pomelo-common + vertx-core + vertx-web-client + logback
  src/main/java/com/github/moxib/pomelo/benchmark/
    BenchmarkMain.java        # main 入口：解析参数 → 编排三阶段 → 输出指标
    UserRegistry.java         # HTTP 批量注册（并发，返回 userId+token 列表）
    ImClient.java             # TCP IM 客户端：连接/登录/加好友/发消息 + 请求响应匹配
    Metrics.java              # 延迟统计：QPS / P50 / P99 / max / 成功率
```

根 `pom.xml` 的 `<modules>` 增加 `pomelo-benchmark`。

## 核心实现

### 1. ImClient（TCP 客户端）

```java
class ImClient {
  private final Vertx vertx;
  private final NetSocket socket;
  private final Map<String, CompletableFuture<ImMessage>> pending; // messageId → 响应

  static Future<ImClient> connect(Vertx vertx, String host, int port) {
    // NetClient.connect，然后挂 RecordParser 处理粘包
    // 发送：socket.write(msg.encodeToWire())   ← 已含长度前缀
    // 接收：RecordParser.newFixed(4) → 读 4 字节长度 → fixedSizeMode(size) → readFromWire
  }

  Future<ImMessage> request(int cmd, JsonObject body, Map<String,String> headers) {
    // 生成 messageId → 注册 pending → 发送 → 返回 future
  }

  Future<Void> login(String token, String userId, String userName);   // AUTH_REQ
  Future<Void> addFriend(String selfId, String friendId);              // FRIEND_ADD_REQ
  Future<Void> acceptFriend(String selfId, String friendId);           // FRIEND_ACCEPT_REQ
  Future<Void> sendMessage(String selfId, String peerId, String content); // C2C_REQ
}
```

- 粘包处理逻辑**直接照搬** `TcpGatewayVerticle.getTcpHandler()` 的 `RecordParser` 模式（只是方向反过来）。
- 响应按 `messageId` 匹配 `pending`；推送（`C2C_NOTIFY`）无 pending 对应，忽略或计数。

### 2. UserRegistry（HTTP 批量注册）

```java
class UserRegistry {
  // WebClient 并发 POST /api/user/register，body {userName: "bench_%d", nickname, password}
  // 并发度用 AtomicInteger + Future 池维持；返回 List<UserInfo(userId, token, userName)>
}
```

### 3. Metrics（延迟统计）

```java
class Metrics {
  // record(long startNanos)；结束计算 QPS + P50/P99/max + 成功率
  // 参考 SeqSvrPerfBenchmark 的统计方式（Arrays.sort + 分位数）
}
```

### 4. BenchmarkMain（编排）

```
参数解析（--host --api-port --tcp-port --users --friends --messages --concurrency）
1. 注册阶段：UserRegistry 批量注册 N 用户 → 输出注册 QPS
2. 连接阶段：每个用户建立一个 ImClient 连接 + 登录
3. 加好友阶段：前 2F 个用户两两配对，A 申请 B、B 接受 A（申请+接受都计延迟）
4. 发消息阶段：在 F 对好友之间互发 M 条 C2C_REQ（并发 --concurrency）
5. 各阶段独立输出 QPS/P50/P99/max/成功率
```

- 并发模型参考 `SeqSvrPerfBenchmark`：`AtomicInteger` 计数 + 每个完成回调触发下一个（维持固定并发度）。
- 消息体用 `JsonObject` 构建（`codecId=1`），无需依赖 `pomelo-logic-server` 的 DTO。

## 验证

1. **启动依赖**：`docker compose up -d`（postgres/redis/seqsvr/logic/gateway 全套）
2. **运行压测**：`./mvnw -pl pomelo-benchmark compile exec:java -Dexec.mainClass=...BenchmarkMain -Dexec.args="--users 1000 --friends 500 --messages 10000 --concurrency 100"`
3. **预期输出**：三阶段各自的 QPS / P50 / P99 / max / 成功率
4. **冒烟**：先用小数量（`--users 10 --friends 5 --messages 100`）验证链路正确（注册返回 userId、加好友 code=0、消息响应 code=0），再放大量
