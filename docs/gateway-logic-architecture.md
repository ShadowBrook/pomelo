# Gateway ↔ Logic-Server 分层架构设计

> 目标：将业务处理（handler）从 gateway 模块剥离为独立的 logic-server 模块，gateway 退化为薄协议适配层。模块间通过 Vert.x EventBus 通讯，支持分布式部署。

---

## 1. 当前状态分析

### 1.1 当前模块边界问题

Gateway 模块目前混合了两种职责：

| 职责 | 归属 | 类 | 说明 |
|------|------|-----|------|
| 连接管理 | 应属 Gateway | `TcpGatewayVerticle`, `WsGatewayVerticle`, `Connection` | TCP/WS 监听、粘包处理、连接生命周期 |
| 协议编解码 | 应属 Gateway | `ImMessage`, `CodecRegistry`, `ProtobufCodec`, `JsonCodec` | 线协议二进制 ↔ ImMessage |
| 消息分发 | 应属 Gateway | `MessageDispatcher` | cmd → Handler 路由 |
| 本地会话 | 应属 Gateway | `SessionRegistry` | userId ↔ Connection 映射（仅本地节点有效） |
| **业务处理** | **应剥离** | `LoginHandler`, `C2CMessageHandler`, `AckReqHandler`, `CtrlReqHandler`, `HeartbeatHandler` | 消息校验、存储、转发、ACK 确认 |
| **数据访问** | **应剥离** | `MessageRepository` | PostgreSQL CRUD |
| **ID 生成** | **应剥离** | `RedisIdGenerator` | Redis 分布式 ID |

### 1.2 PullMessageHandler 暴露的问题

新加入的 `PullMessageHandler` 已经暴露出构造函数膨胀的趋势：

```java
// 当前 — 一个 Handler 注入了过多依赖
public PullMessageHandler(Vertx vertx, CodecRegistry codecRegistry,
                          SessionRegistry sessionRegistry, MessageRepository messageRepo,
                          IdGenerator idGenerator)
```

这说明业务逻辑正在变重，继续放在 gateway 模块会导致：
- Gateway 模块承载太多数据库/缓存依赖，资源消耗不可控
- 分布式部署时，长连接节点和计算密集型业务无法独立扩缩容
- 每新增一个 Handler 都需要改动 gateway 模块

---

## 2. 目标架构

### 2.1 模块划分

```
┌─────────────────────────────────────────────────────────┐
│                    pomelo-gateway                        │
│  职责：连接管理 + 协议适配 + 推送投递                      │
│                                                          │
│  Connection / ImMessage / CodecRegistry                  │
│  TcpGatewayVerticle / WsGatewayVerticle                  │
│  SessionRegistry (本地 userId → Connection)               │
│  GatewayDispatchHandler (EventBus → local connection)    │
└────────────┬────────────────────────────────────────────┘
             │  EventBus (clustered via Hazelcast)
             │
┌────────────▼────────────────────────────────────────────┐
│                  pomelo-logic-server                     │
│  职责：业务逻辑 + 数据访问 + 消息路由 + ID 生成             │
│                                                          │
│  LogicVerticle (EventBus consumer)                       │
│  AuthService / C2CService / AckService / PullService     │
│  MessageRepository (PostgreSQL)                          │
│  RedisIdGenerator (Redis)                                │
└──────────────────────────────────────────────────────────┘
```

### 2.2 部署拓扑

```
            Client A                    Client B
               │                           │
          TCP/WS                        TCP/WS
               │                           │
        ┌──────▼──────┐             ┌──────▼──────┐
        │  Gateway-1   │             │  Gateway-2   │
        │  :9000/9001  │             │  :9000/9001  │
        │  SessionReg  │             │  SessionReg  │
        └──────┬───────┘             └──────┬───────┘
               │                            │
               └────────┬───────────────────┘
                        │
              EventBus Cluster (Hazelcast)
                        │
          ┌─────────────┼─────────────┐
          │             │             │
    ┌─────▼─────┐ ┌─────▼─────┐ ┌─────▼─────┐
    │ Logic-1   │ │ Logic-2   │ │ Logic-3   │
    │ Auth      │ │ C2C       │ │ Pull/ACK  │
    │ C2C       │ │ Auth      │ │ C2C       │
    └─────┬─────┘ └─────┬─────┘ └─────┬─────┘
          │             │             │
          └────────┬────┴─────────────┘
                   │
          ┌────────▼────────┐
          │  PostgreSQL     │
          │  Redis          │
          └─────────────────┘
```

- **Gateway 节点**：水平扩展承载更多长连接，资源消耗主要是内存和网络 IO
- **Logic-Server 节点**：独立扩展承载业务计算，资源消耗主要是 CPU 和 DB 连接

---

## 3. EventBus 通讯模式

### 3.1 请求-响应（Request-Reply）

适用场景：客户端主动发起的请求，需要同步响应。

```
Gateway                          Logic-Server
  │                                  │
  │  eventBus.request(               │
  │    "logic.c2c",    ──────────►   │  C2CService.handle(msg)
  │    wireMessage,                  │  │
  │    reply -> {                    │  │ 存储 → 生成 ID → 构造 C2CResp
  │      connection.write(reply)     │  │
  │    })               ◄──────────  │  replyHandler.reply(c2cResp)
  │                                  │
```

**EventBus 地址约定**：

| Address | 说明 |
|---------|------|
| `logic.c2c` | 单聊发送请求 |
| `logic.c2g` | 群聊发送请求 |
| `logic.auth` | 登录/登出请求 |
| `logic.ctrl` | 控制命令请求 |
| `logic.ack` | ACK 确认请求 |
| `logic.pull` | 离线消息拉取 |
| `logic.ping` | 心跳请求 |

Vert.x EventBus 的 `request()` 方法自动处理超时（默认 30s），返回失败时 gateway 向客户端发送错误响应。

### 3.2 发布-订阅（Publish-Subscribe）

适用场景：Logic-Server 需要推送消息给在线用户，但不知道用户连接在哪个网关节点。

```
Logic-Server                        所有 Gateway 节点
  │                                  ┌─────────┬─────────┐
  │  eventBus.publish(               │ GW-1    │ GW-2    │
  │    "gateway.push", ──────────►   │         │         │
  │    pushEnvelope)                 │ 检查 B  │ 检查 B  │
  │                                  │ 在线？  │ 在线？  │
  │                                  │  ✓      │  ✗      │
  │                                  │ 发送    │ 忽略    │
  │                                  └─────────┴─────────┘
```

**为什么用 publish 而不是 send**：
- send 是点对点（round-robin），只送达一个节点
- publish 广播给所有订阅了该地址的节点
- 每个 gateway 节点本地检查 SessionRegistry 决定是否投递
- 虽然广播有开销，但避免了维护中心化路由表的复杂性

### 3.3 推送消息结构

Logic-Server 发给 gateway 的推送消息需要包含路由信息：

```java
/**
 * Logic-Server → Gateway 的推送信封
 * 在 EventBus 上以 JSON 或序列化对象传输
 */
public class PushEnvelope {
    String targetUserId;      // 必须：推送给哪个用户
    int    cmd;               // 必须：推送的命令字（C2C_NOTIFY / ACK_NOTIFY / CTRL_NOTIFY）
    byte[] body;              // 必须：业务消息体（已编码的 protobuf bytes）
    byte   codecId;           // 必须：编码类型
    String correlationMsgId;  // 可选：关联的原始请求 messageId
}
```

Gateway 收到 PushEnvelope 后：
1. 从本地 `SessionRegistry` 查找 `targetUserId` 的 Connection
2. 构造 ImMessage（填充 cmd、codecId、body）
3. `connection.write(message.encodeToWire())`
4. 如果用户不在线 → 忽略（消息已在 logic-server 持久化，用户上线后通过 PULL 拉取）

---

## 4. Session 路由问题

### 4.1 问题描述

用户 B 连接在 Gateway-1，Logic-Server 处理完 C2CReq 后需要推送 C2CNotify 给 B。Logic-Server 不知道 B 在哪台 gateway 上。

### 4.2 方案对比

| 方案 | 实现 | 优点 | 缺点 |
|------|------|------|------|
| **A: 广播到所有 Gateway** (推荐) | Logic-Server 发布到 `gateway.push`，所有 gateway 订阅，各自检查本地 SessionRegistry | 简单，无单点，自愈合 | 广播量随消息量增长，但单节点开销极小（仅内存查表） |
| B: 中心化路由表 | Redis 存储 `userId → gatewayNodeId`，login 时写入，离线时删除。Logic-Server 查表后 send 到指定 gateway | 精确送达 | Redis 变成关键依赖，需处理节点宕机时的脏数据 |
| C: Hazelcast 分布式 Map | 用 Hazelcast IMap 替代 Redis 做路由表 | 与 EventBus 共用一个集群 | Hazelcast 也有脑裂问题，且引入额外复杂度 |

**推荐方案 A**，理由：
- IM 场景下推送消息的开销远小于消息持久化，广播不会成为瓶颈
- 无需额外依赖或路由表维护
- Gateway 节点数量通常有限（几十台），广播开销可控
- 节点故障自愈合：gateway 宕机后，客户端自动重连到其他 gateway 并重新 login
- 未来如果 gateway 节点数增长到百台以上，可以平滑切换到方案 B

---

## 5. 代码层面改造

### 5.1 Gateway 模块改造

**MessageDispatcher 改造**：不再实例化 Handler，改为通过 EventBus 转发。

```java
public class MessageDispatcher {

    private final Vertx vertx;
    private final SessionRegistry sessionRegistry;

    public MessageDispatcher(Vertx vertx, SessionRegistry sessionRegistry) {
        this.vertx = vertx;
        this.sessionRegistry = sessionRegistry;
        // 订阅推送地址
        vertx.eventBus().consumer("gateway.push", this::onPushMessage);
    }

    /** 收到网关推送，投递给本地连接的用户 */
    private void onPushMessage(Message<JsonObject> msg) {
        PushEnvelope env = msg.body().mapTo(PushEnvelope.class);
        Connection conn = sessionRegistry.getConnection(env.getTargetUserId());
        if (conn != null) {
            ImMessage imMsg = ImMessage.builder()
                .magic(ImMessage.MAGIC_NUMBER)
                .version(ImMessage.WIRE_PROTOCOL_VERSION)
                .codecId(env.getCodecId())
                .cmd(env.getCmd())
                .messageId(UUID.randomUUID().toString())
                .body(env.getBody())
                .build();
            conn.write(imMsg.encodeToWire());
        }
        // 用户不在线 → 忽略，消息已在 logic-server 持久化
    }

    /** 将客户端请求转发到 logic-server */
    public void dispatch(Connection connection, ImMessage message) {
        // 用 cmd 构造 EventBus 地址
        String address = cmdToAddress(message.getCmd());
        if (address == null) {
            handleUnknownCmd(connection, message);
            return;
        }
        // 将 ImMessage 编码为 wire bytes 通过 EventBus 发送
        vertx.eventBus().request(address, encodeForBus(message), reply -> {
            if (reply.succeeded()) {
                ImMessage response = decodeFromBus((Buffer) reply.result().body());
                connection.write(response.encodeToWire());
            } else {
                sendErrorToClient(connection, message, reply.cause().getMessage());
            }
        });
    }
}
```

**MessageDispatcher 不再需要 `handlerRegistry`**，cmd → EventBus address 的映射足够：

```java
private static String cmdToAddress(int cmd) {
    return switch (cmd) {
        case CMD_C2C_REQ_VALUE  -> "logic.c2c";
        case CMD_C2G_REQ_VALUE  -> "logic.c2g";
        case CMD_AUTH_REQ_VALUE -> "logic.auth";
        case CMD_LOGOUT_REQ_VALUE -> "logic.auth";
        case CMD_CTRL_REQ_VALUE -> "logic.ctrl";
        case CMD_ACK_REQ_VALUE  -> "logic.ack";
        case CMD_PULL_REQ_VALUE -> "logic.pull";
        case CMD_PING_VALUE     -> "logic.ping";
        default -> null;  // unknown cmd
    };
}
```

### 5.2 Logic-Server 模块结构

```
pomelo-logic-server/
├── pom.xml
└── src/main/java/com/github/moxib/pomelo/logic/
    ├── LogicVerticle.java          # 注册所有 EventBus consumer
    ├── service/
    │   ├── AuthService.java        # 登录/登出业务
    │   ├── C2CService.java         # 单聊：存储 + 推送 + 回复
    │   ├── C2GService.java         # 群聊：存储 + 扩散 + 回复
    │   ├── AckService.java         # ACK：更新已读 + 推送
    │   ├── CtrlService.java        # 控制命令
    │   ├── PullService.java        # 离线消息拉取
    │   └── HeartbeatService.java   # 心跳处理
    └── infrastructure/
        ├── MessageRepository.java  # 从 gateway 模块迁移
        └── RedisIdGenerator.java   # 从 utils 迁移（或共享到 common）
```

**LogicVerticle 示例**：

```java
public class LogicVerticle extends VerticleBase {

    private C2CService c2cService;
    private AckService ackService;
    // ... 其他 service

    @Override
    public Future<?> start() {
        // 初始化服务和依赖
        c2cService = new C2CService(vertx, messageRepo, idGenerator);
        ackService = new AckService(vertx, messageRepo);
        // ...

        var bus = vertx.eventBus();
        bus.consumer("logic.c2c",  this::handleC2C);
        bus.consumer("logic.ack",  this::handleAck);
        bus.consumer("logic.auth", this::handleAuth);
        bus.consumer("logic.pull", this::handlePull);
        bus.consumer("logic.ping", this::handlePing);
        // ...

        return Future.succeededFuture();
    }

    private void handleC2C(Message<Buffer> msg) {
        ImMessage request = decodeFromBuffer(msg.body());
        c2cService.process(request).onComplete(ar -> {
            if (ar.succeeded()) {
                msg.reply(encodeToBuffer(ar.result())); // C2CResp 返回给 gateway
            } else {
                msg.fail(500, ar.cause().getMessage());
            }
        });
    }
}
```

### 5.3 C2CService — 完整流转示例

```java
public class C2CService {

    private final Vertx vertx;
    private final MessageRepository messageRepo;
    private final IdGenerator idGenerator;

    public Future<ImMessage> process(ImMessage request) {
        // 1. 解码 Proto body → C2CReq
        C2CReq req = protobufDecode(request.getBody());

        // 2. 生成消息 ID
        return idGenerator.nextId().compose(messageId -> {
            // 3. 持久化
            return messageRepo.saveC2CMessage(messageId,
                req.getSenderId(), req.getRecipientId(),
                req.getMessage().getMsgType().getNumber(),
                req.getMessage().getContent().toStringUtf8(),
                System.currentTimeMillis()
            ).compose(v -> {
                // 4. 构造 C2CResp（同步返回给发送方）
                C2CResp c2cResp = C2CResp.newBuilder()
                    .setCode(0).setMessage("success")
                    .setMessageId(messageId)
                    .setServerTime(System.currentTimeMillis())
                    .build();

                ImMessage response = wrapResponse(request, CMD_C2C_RESP_VALUE, c2cResp.toByteArray());

                // 5. 构造 C2CNotify → 发布给所有 gateway 投递
                C2CNotify notify = C2CNotify.newBuilder()
                    .setSenderId(req.getSenderId())
                    .setRecipientId(req.getRecipientId())
                    .setMessage(req.getMessage())
                    .build();

                PushEnvelope push = new PushEnvelope(
                    req.getRecipientId(),
                    CMD_C2C_NOTIFY_VALUE,
                    notify.toByteArray(),
                    (byte) 0 // protobuf
                );

                // 发布推送 — 所有 gateway 订阅者都会收到，各自检查目标用户是否在线
                vertx.eventBus().publish("gateway.push", JsonObject.mapFrom(push));

                return Future.succeededFuture(response);
            });
        });
    }
}
```

---

## 6. 需要共享的公共模块

两个模块的共同依赖抽取到 `pomelo-common`：

```
pomelo-common/
└── src/main/java/com/github/moxib/pomelo/
    ├── common/
    │   └── ImMessage.java         # 线协议消息（含 wire 编解码）
    ├── codec/
    │   ├── MessageCodec.java
    │   ├── CodecRegistry.java
    │   ├── ProtobufCodec.java     # proto 静态 Parser 注册表
    │   └── JsonCodec.java
    ├── proto/                      # 所有 protobuf 生成的 Java 类
    │   ├── common/CommonProto.java
    │   ├── auth/AuthProto.java
    │   ├── chat/ChatProto.java
    │   └── ...
    └── model/
        └── PushEnvelope.java      # Gateway ↔ Logic 的推送信封
```

### 模块依赖关系

```
pomelo-common (ImMessage, Proto, Codec)
    ↑                   ↑
    │                   │
pomelo-gateway    pomelo-logic-server
(Connection,       (Service, Repository,
 SessionRegistry)   IdGenerator)
```

### Maven 多模块结构

```
pomelo/
├── pom.xml                  # parent POM (packaging: pom)
├── pomelo-common/
│   └── pom.xml
├── pomelo-gateway/
│   └── pom.xml
└── pomelo-logic-server/
    └── pom.xml
```

---

## 7. 分布式场景下的关键问题

### 7.1 EventBus 序列化

Vert.x 集群模式下，EventBus 消息需要在节点间传输。默认使用 Hazelcast 序列化，但 `ImMessage` 和 `PushEnvelope` 必须实现 `Serializable` 或注册自定义 `MessageCodec`。

**推荐方案**：在 EventBus 上传输已编码的 `Buffer`（byte[]），避免对象序列化问题。两端都已有 `ImMessage.encodeToWire()` / `readFromWire()`。

```java
// Gateway 发送
Buffer wire = message.encodeToWire();
eventBus.request("logic.c2c", wire);

// Logic-server 接收
ImMessage msg = new ImMessage();
msg.readFromWire((Buffer) message.body());
```

这样 EventBus 上只传输 `io.vertx.core.buffer.Buffer`（Vert.x 已内置支持）。

### 7.2 集群发现

Hazelcast 集群发现是分布式部署的关键。需要配置：

```xml
<!-- hazelcast.xml -->
<network>
    <join>
        <!-- 开发环境: 组播自动发现 -->
        <multicast enabled="true"/>
        <!-- 生产环境: TCP-IP 静态成员列表 或 Kubernetes API -->
        <tcp-ip enabled="false">
            <member>logic-1:5701</member>
            <member>logic-2:5701</member>
        </tcp-ip>
    </join>
</network>
```

### 7.3 节点扩缩容

- **Gateway 新增**：新节点启动 → 加入 Hazelcast 集群 → 订阅 `gateway.push` → 对外暴露 TCP/WS 端口。客户端通过负载均衡器连接。
- **Gateway 下线**：优雅关闭 → 关闭 TCP/WS 监听 → 等待已有连接自然断开 → 离开集群。客户端自动重连到其他节点。
- **Logic-Server 新增**：新节点启动 → 加入集群 → 注册 EventBus consumer → 立即参与 `logic.*` 消息的 round-robin 分发。
- **Logic-Server 下线**：优雅关闭 → 取消 consumer 注册 → 等待进行中的请求完成 → 离开集群。

### 7.4 消息可靠性

| 场景 | 处理 |
|------|------|
| Logic-Server 处理中宕机 | EventBus `request()` 超时 → Gateway 向客户端返回错误 → 客户端重试 |
| C2CNotify 推送时目标 Gateway 宕机 | 消息已持久化，B 重连到新 Gateway 后通过 PULL 拉取 |
| 广播 `gateway.push` 未覆盖所有节点 | Gateway 重连后需主动从 logic-server 拉取未投递的推送（可选优化） |

---

## 8. 实施建议

### 分阶段迁移

**Phase 1 — 模块拆分（无业务逻辑变更）**
1. 创建 `pomelo-common` 模块，迁移 `ImMessage`、`codec` 包、`proto` 包
2. 创建 `pomelo-logic-server` 模块，迁移 handler 到 service
3. Gateway `MessageDispatcher` 改为 EventBus 转发
4. 验证单节点部署（单 JVM 内 EventBus 无需集群）

**Phase 2 — 业务补全**
1. 在 service 中实现真实的业务逻辑（替换 TODO 桩代码）
2. 完善推送机制（`gateway.push` consumer）
3. `SessionRegistry` 与 login/logout 集成

**Phase 3 — 分布式部署**
1. 配置 Hazelcast 集群发现
2. 压力测试验证广播推送的性能
3. 如需优化，引入方案 B（中心化路由表）

### 当前代码可直接利用的部分

- `PushEnvelope` 思想已存在于 `C2CNotify` / `AckNotify` 的 proto 定义中 — 它们本身就是推送信封
- `SessionRegistry` 已实现本地会话管理，直接可用
- `MessageRepository` 的 PostgreSQL schema 已定义（参考 `db/` 目录下的 DDL）
- `ProtobufCodec` 的静态注册表不需要改动
