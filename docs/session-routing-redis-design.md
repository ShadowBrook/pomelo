# Redis Session 路由方案详细设计

> 用 Redis 存储 `userId → gatewayNodeId` 路由表，实现 logic-server 到 gateway 的精确推送。

---

## 1. 总体数据流

```
┌──────────────────────┐         ┌──────────────────────┐
│   Gateway-1          │         │   Gateway-2          │
│   nodeId: "gw-1"     │         │   nodeId: "gw-2"     │
│                      │         │                      │
│  SessionRegistry     │         │  SessionRegistry     │
│  (userId→Connection) │         │  (userId→Connection) │
│        ↕             │         │        ↕             │
│  SessionRouteService │         │  SessionRouteService │
│  (Redis 读写)        │         │  (Redis 读写)        │
└──────────┬───────────┘         └──────────┬───────────┘
           │                                │
           │         Redis                   │
           │  ┌─────────────────────┐       │
           │  │ session:A → "gw-1"  │       │
           │  │ session:B → "gw-2"  │       │
           │  └─────────────────────┘       │
           │                                │
           └────────────┬───────────────────┘
                        │
              EventBus Cluster
                        │
           ┌────────────▼───────────┐
           │   Logic-Server-1       │
           │                        │
           │  1. 查 Redis: B→"gw-2" │
           │  2. eventBus.send(     │
           │     "gw-2.push", msg)  │
           └────────────────────────┘
```

每一步数据流：

```
Login:  Gateway 写本地 SessionRegistry
             ╲
              → 写 Redis session:{userId} → {nodeId}
              → 写 Redis node:{nodeId}:users → Set{userId}  (用于宕机清理)

Push:   Logic-Server 读 Redis session:{userId} → nodeId
             ╲
              → eventBus.send(nodeId + ".push", msg) 精确投递
              → 用户离线(session不存在) → 跳过, 消息已在DB待PULL

Logout: Gateway 删本地 SessionRegistry
             ╲
              → 删 Redis session:{userId}
              → 从 Redis node:{nodeId}:users 移除 userId
```

---

## 2. Redis Key 设计

### 2.1 Key 结构

```
# 用户 → 网关节点映射（核心路由）
Key:   session:{userId}
Type:  String (JSON)
TTL:   300s (5分钟, 由心跳续期)
Value: {
    "nodeId":     "gw-1",            // string, 网关节点ID
    "connId":     "f47ac10b",        // string, 连接ID (SessionRegistry 内部使用, 调试用)
    "loginTime":  1715678900000,     // long, Unix毫秒
    "deviceType": "ios",             // string, 可选
    "clientVersion": "1.2.0"         // string, 可选
}

# 网关节点 → 在线用户集合（用于宕机清理）
Key:   node:{nodeId}:users
Type:  Set
TTL:   无 (节点正常下线时主动删除)
Members: ["user_001", "user_002", ...]
```

### 2.2 Key 常量定义

```java
public final class SessionKeys {
    private SessionKeys() {}

    /** session:{userId} — 用户路由映射 */
    public static final String SESSION_PREFIX = "session:";

    /** node:{nodeId}:users — 节点在线用户集合 */
    public static final String NODE_USERS_PREFIX = "node:";

    /** session:{userId} TTL (秒) */
    public static final int SESSION_TTL_SECONDS = 300;

    /** 心跳续期间隔 (秒), 应为 TTL 的 1/3, 即 ~100s */
    public static final int HEARTBEAT_RENEW_INTERVAL_SECONDS = 100;

    public static String sessionKey(String userId) {
        return SESSION_PREFIX + userId;
    }

    public static String nodeUsersKey(String nodeId) {
        return NODE_USERS_PREFIX + nodeId + ":users";
    }
}
```

### 2.3 为什么选择 String (JSON) 而非 Hash

| 维度 | String (JSON) | Hash |
|------|---------------|------|
| TTL | ✅ `SETEX` 一条命令 | ❌ Hash 不支持整体 TTL，需额外 `EXPIRE` |
| 原子性 | ✅ 单条命令，天然原子 | ❌ `HSET` + `EXPIRE` 两步，Lua 脚本才能原子 |
| 字段扩展 | 新增字段不破坏结构 | 同左 |
| 字段级更新 | ❌ 需整体覆盖 | ✅ `HINCRBY`, `HSET` 单字段 |
| 本项目需求 | `loginTime` 只写一次，`nodeId`/`connId` 整体覆盖 | 无字段级更新需求 |

本项目 session 数据量不大且无字段级更新需求，**String (JSON) 更合适**。

---

## 3. 核心类设计

### 3.1 模块归属

```
pomelo-common/
└── SessionInfo.java           # 共享的数据模型

pomelo-gateway/
├── SessionRegistry.java       # 已有, 本地 userId→Connection 映射
└── SessionRouteService.java   # 新增, Redis 读写 + 心跳续期

pomelo-logic-server/
└── RoutePushService.java      # 新增, 读 Redis → 精确推送
```

### 3.2 SessionInfo

```java
package com.github.moxib.pomelo.common;

/**
 * Redis session 路由记录。
 * 两个模块共享的数据结构，JSON 序列化后存入 Redis。
 */
public class SessionInfo {

    private String nodeId;        // 网关节点ID, e.g. "gw-1"
    private String connId;        // 连接ID, 仅调试用
    private long   loginTime;     // 登录时间, Unix毫秒
    private String deviceType;    // 可选, 设备类型
    private String clientVersion; // 可选, 客户端版本

    // 无参构造 (Jackson 反序列化需要)
    public SessionInfo() {}

    public SessionInfo(String nodeId, String connId, long loginTime) {
        this.nodeId = nodeId;
        this.connId = connId;
        this.loginTime = loginTime;
    }

    // getters & setters ...
    public String getNodeId() { return nodeId; }
    public void   setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getConnId() { return connId; }
    public long   getLoginTime() { return loginTime; }
    public String getDeviceType() { return deviceType; }
    public void   setDeviceType(String deviceType) { this.deviceType = deviceType; }
    public String getClientVersion() { return clientVersion; }
    public void   setClientVersion(String clientVersion) { this.clientVersion = clientVersion; }
}
```

### 3.3 SessionRouteService（Gateway 侧）

```java
package com.github.moxib.pomelo.gateway;

import com.github.moxib.pomelo.common.SessionInfo;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static io.vertx.redis.client.Command.*;
import static io.vertx.redis.client.Request.cmd;

/**
 * Redis Session 路由服务。
 *
 * 职责：
 *  - login 时写入 Redis session:{userId} + node:{nodeId}:users
 *  - 周期性心跳续期 TTL
 *  - logout / 断连时删除 Redis 记录
 *  - 节点宕机时由幸存节点清理（通过 Redis SCAN 补偿）
 *
 * 使用模式参考同项目的 RedisIdGenerator。
 */
public class SessionRouteService {

    private static final Logger log = LoggerFactory.getLogger(SessionRouteService.class);

    private final Vertx vertx;
    private final String nodeId;          // 当前网关节点唯一ID
    private final Redis redis;
    private volatile RedisConnection connection;
    private volatile boolean connected;

    /**
     * @param vertx     Vertx 实例
     * @param nodeId    当前网关节点唯一ID，如 "gw-1", "gw-2"
     * @param redisOpts Redis 连接配置
     */
    public SessionRouteService(Vertx vertx, String nodeId, RedisOptions redisOpts) {
        this.vertx = vertx;
        this.nodeId = nodeId;
        this.redis = Redis.createClient(vertx, redisOpts);
    }

    // ──────────── 生命周期 ────────────

    /** 启动：连接 Redis */
    public Future<Void> start() {
        return redis.connect()
            .onSuccess(conn -> {
                this.connection = conn;
                this.connected = true;
                log.info("SessionRouteService 已连接 Redis, nodeId={}", nodeId);
            })
            .onFailure(e -> log.error("SessionRouteService Redis 连接失败", e))
            .mapEmpty();
    }

    /** 停止：关闭 Redis 连接 */
    public Future<Void> stop() {
        connected = false;
        if (connection != null) {
            return connection.close()
                .onSuccess(v -> log.info("SessionRouteService 已关闭, nodeId={}", nodeId));
        }
        return Future.succeededFuture();
    }

    // ──────────── Session 写操作 ────────────

    /**
     * 用户上线注册。
     * 在 SessionRegistry.register() 之后调用。
     * 使用 Lua 脚本保证写入 session + 添加到 node users 集合的原子性。
     */
    public Future<Void> register(String userId, String connId) {
        if (!connected) {
            log.warn("Redis 未连接，跳过 session 注册: userId={}", userId);
            return Future.succeededFuture();
        }

        SessionInfo info = new SessionInfo(nodeId, connId, System.currentTimeMillis());
        String json = JsonObject.mapFrom(info).encode();

        return connection.send(cmd(EVAL)
            .arg(REGISTER_SCRIPT)
            .arg("2")                              // 2 个 key
            .arg(SessionKeys.sessionKey(userId))    // KEYS[1]
            .arg(SessionKeys.nodeUsersKey(nodeId)) // KEYS[2]
            .arg(json)                              // ARGV[1]: session value
            .arg(String.valueOf(SessionKeys.SESSION_TTL_SECONDS))  // ARGV[2]: TTL
        ).onSuccess(resp -> log.debug("session 注册: userId={}, nodeId={}", userId, nodeId))
         .onFailure(e  -> log.error("session 注册失败: userId={}", userId, e))
         .mapEmpty();
    }

    /**
     * 用户下线注销。
     * 在 SessionRegistry.unregister() 之后调用。
     */
    public Future<Void> unregister(String userId) {
        if (!connected) return Future.succeededFuture();

        return connection.send(cmd(EVAL)
            .arg(UNREGISTER_SCRIPT)
            .arg("2")
            .arg(SessionKeys.sessionKey(userId))
            .arg(SessionKeys.nodeUsersKey(nodeId))
            .arg(userId)
        ).onSuccess(resp -> log.debug("session 注销: userId={}", userId))
         .onFailure(e  -> log.error("session 注销失败: userId={}", userId, e))
         .mapEmpty();
    }

    // ──────────── 心跳续期 ────────────

    /**
     * 启动周期性心跳续期定时器。
     * 在 GatewayVerticle.start() 中调用。
     */
    public void startHeartbeatRenewal() {
        vertx.setPeriodic(
            SessionKeys.HEARTBEAT_RENEW_INTERVAL_SECONDS * 1000L,
            id -> renewAllSessions()
        );
        log.info("Session 心跳续期已启动: interval={}s", SessionKeys.HEARTBEAT_RENEW_INTERVAL_SECONDS);
    }

    /**
     * 对本节点所有在线用户批量续期。
     * 使用 Lua 脚本遍历 node:{nodeId}:users 集合，为每个 session 设置 TTL。
     */
    private void renewAllSessions() {
        if (!connected) return;

        connection.send(cmd(EVAL)
            .arg(RENEW_SCRIPT)
            .arg("1")
            .arg(SessionKeys.nodeUsersKey(nodeId))
            .arg(SessionKeys.SESSION_PREFIX)
            .arg(String.valueOf(SessionKeys.SESSION_TTL_SECONDS))
        ).onSuccess(resp -> {
            long count = resp.toLong();
            if (count > 0) {
                log.debug("Session 续期完成: count={}", count);
            }
        }).onFailure(e -> log.error("Session 续期失败", e));
    }

    // ──────────── 查询 ────────────

    /** 查询用户所在网关节点（logic-server 侧也在用，但这里用于调试） */
    public Future<String> getNodeId(String userId) {
        if (!connected) return Future.failedFuture("Redis not connected");

        return connection.send(cmd(GET).arg(SessionKeys.sessionKey(userId)))
            .map(resp -> {
                if (resp == null) return null;
                String json = resp.toString();
                return new JsonObject(json).getString("nodeId");
            });
    }
}
```

### 3.4 Lua 脚本

```java
// SessionRouteService 类的静态脚本常量

/**
 * 注册脚本:
 *   KEYS[1] = session:{userId}
 *   KEYS[2] = node:{nodeId}:users
 *   ARGV[1] = session JSON
 *   ARGV[2] = TTL seconds
 *
 * 操作:
 *   1. SETEX session:{userId} TTL JSON
 *   2. SADD node:{nodeId}:users userId
 *   3. EXPIRE node:{nodeId}:users <large>  (刷新节点集合的 TTL 以防残留)
 */
private static final String REGISTER_SCRIPT = """
    redis.call('SETEX', KEYS[1], ARGV[2], ARGV[1])
    redis.call('SADD',  KEYS[2], KEYS[1]:match('session:(.+)'))
    redis.call('EXPIRE', KEYS[2], 86400)
    return 1
    """;

/**
 * 注销脚本:
 *   KEYS[1] = session:{userId}
 *   KEYS[2] = node:{nodeId}:users
 *   ARGV[1] = userId
 *
 * 操作:
 *   1. DEL session:{userId}
 *   2. SREM node:{nodeId}:users userId
 */
private static final String UNREGISTER_SCRIPT = """
    redis.call('DEL', KEYS[1])
    redis.call('SREM', KEYS[2], ARGV[1])
    return 1
    """;

/**
 * 批量续期脚本:
 *   KEYS[1] = node:{nodeId}:users
 *   ARGV[1] = session: 前缀
 *   ARGV[2] = TTL seconds
 *
 * 操作:
 *   遍历 node:{nodeId}:users 的所有 userId,
 *   对已存在的 session:{userId} 执行 EXPIRE
 *
 * 注意: 不存在的 key 会被跳过（用户可能已正常下线但 SREM 尚未执行）
 */
private static final String RENEW_SCRIPT = """
    local users = redis.call('SMEMBERS', KEYS[1])
    local prefix = ARGV[1]
    local ttl = tonumber(ARGV[2])
    local count = 0
    for _, userId in ipairs(users) do
        local key = prefix .. userId
        local exists = redis.call('EXISTS', key)
        if exists == 1 then
            redis.call('EXPIRE', key, ttl)
            count = count + 1
        else
            -- 用户的 session 已过期或已删除，从集合中移除
            redis.call('SREM', KEYS[1], userId)
        end
    end
    return count
    """;
```

### 3.5 与 SessionRegistry 集成

```java
// GatewayVerticle 中集成代码片段

public class WsGatewayVerticle extends VerticleBase {

    private SessionRegistry sessionRegistry;
    private SessionRouteService routeService;

    @Override
    public Future<?> start() {

        // 1. 初始化本地会话注册表
        sessionRegistry = new SessionRegistry();

        // 2. 初始化 Redis 路由服务
        String nodeId = System.getProperty("gateway.node.id", "gw-" + UUID.randomUUID().toString().substring(0, 8));
        routeService = new SessionRouteService(vertx, nodeId, new RedisOptions());

        return routeService.start()
            .compose(v -> {
                // 3. 启动心跳续期
                routeService.startHeartbeatRenewal();

                // 4. 启动 WebSocket server
                // ...
            });
    }

    /** Login 成功后的回调 */
    private void onUserLoggedIn(Connection connection, String userId) {
        String connId = UUID.randomUUID().toString().substring(0, 8);

        // 先注册本地会话
        sessionRegistry.register(userId, connection);

        // 再写入 Redis 路由表
        routeService.register(userId, connId);
    }

    /** 连接断开时的回调 */
    private void onConnectionClosed(Connection connection) {
        // 先清理本地会话
        String userId = sessionRegistry.findUserIdByConnection(connection);
        if (userId != null) {
            sessionRegistry.unregister(userId);
            // 再删除 Redis 路由
            routeService.unregister(userId);
        }
    }
}
```

### 3.6 RoutePushService（Logic-Server 侧）

```java
package com.github.moxib.pomelo.logic;

import com.github.moxib.pomelo.common.SessionInfo;
import com.github.moxib.pomelo.common.PushEnvelope;
import io.vertx.core.Future;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.vertx.redis.client.Command.GET;
import static io.vertx.redis.client.Request.cmd;

/**
 * Logic-Server 侧的推送路由服务。
 *
 * 职责：
 *  - 查 Redis 获得目标用户所在 gateway nodeId
 *  - 通过 EventBus 精确推送到该 gateway 节点
 *  - Redis 不可用时降级为广播
 */
public class RoutePushService {

    private static final Logger log = LoggerFactory.getLogger(RoutePushService.class);

    private final Vertx vertx;
    private final EventBus eventBus;
    private final Redis redis;
    private volatile RedisConnection connection;
    private volatile boolean connected;

    /** EventBus 超时 (毫秒) */
    private static final long PUSH_TIMEOUT_MS = 5000;

    public RoutePushService(Vertx vertx, RedisOptions redisOpts) {
        this.vertx = vertx;
        this.eventBus = vertx.eventBus();
        this.redis = Redis.createClient(vertx, redisOpts);
    }

    public Future<Void> start() {
        return redis.connect()
            .onSuccess(conn -> { this.connection = conn; this.connected = true; })
            .mapEmpty();
    }

    /**
     * 精确推送消息给指定用户。
     *
     * 流程:
     *   1. Redis GET session:{userId} → nodeId
     *   2. 命中 → eventBus.send(nodeId + ".push", pushEnvelope)
     *   3. 未命中 → 用户离线, 标记跳过
     *   4. Redis 异常 → 降级为 eventBus.publish("gateway.push", pushEnvelope)
     */
    public Future<PushResult> pushToUser(String userId, PushEnvelope envelope) {
        if (!connected) {
            return fallbackBroadcast(userId, envelope);
        }

        return connection.send(cmd(GET).arg(SessionKeys.sessionKey(userId)))
            .compose(resp -> {
                if (resp == null) {
                    // 用户离线
                    log.debug("用户离线，跳过推送: userId={}", userId);
                    return Future.succeededFuture(PushResult.offline());
                }

                String json = resp.toString();
                SessionInfo info = new JsonObject(json).mapTo(SessionInfo.class);
                String targetNodeId = info.getNodeId();

                // 精确推送到目标 gateway
                DeliveryOptions opts = new DeliveryOptions()
                    .setSendTimeout(PUSH_TIMEOUT_MS);
                return eventBus.request(targetNodeId + ".push", JsonObject.mapFrom(envelope), opts)
                    .map(reply -> PushResult.delivered(targetNodeId))
                    .otherwise(err -> {
                        log.warn("精确推送失败: nodeId={}, userId={}, reason={}",
                            targetNodeId, userId, err.getMessage());
                        // 可能是目标 gateway 宕机但 session 未过期
                        // 降级为广播
                        return fallbackBroadcast(userId, envelope)
                            .map(r -> PushResult.fallback(targetNodeId))
                            .result();
                    });
            })
            .recoverWith(err -> {
                // Redis 查询失败 → 降级广播
                log.error("Redis 查询失败, 降级为广播: userId={}", userId, err);
                return fallbackBroadcast(userId, envelope);
            });
    }

    /** 降级：广播到所有 gateway */
    private Future<PushResult> fallbackBroadcast(String userId, PushEnvelope envelope) {
        envelope.setTargetUserId(userId); // 确保 targetUserId 已设置
        eventBus.publish("gateway.push", JsonObject.mapFrom(envelope));
        log.debug("广播推送完成: userId={}", userId);
        return Future.succeededFuture(PushResult.broadcast());
    }
}

/** 推送结果 */
public enum PushResult {
    DELIVERED,    // 精确投递成功
    OFFLINE,      // 用户离线，跳过
    FALLBACK,     // 降级广播
    ;

    private String nodeId;
    // builder 方法省略...
}
```

### 3.7 PushEnvelope

```java
package com.github.moxib.pomelo.common;

import io.vertx.core.json.JsonObject;

/**
 * Logic-Server → Gateway 的推送信封。
 * 通过 EventBus 传输。
 */
public class PushEnvelope {

    private String targetUserId;     // 目标用户ID
    private int    cmd;              // ImMessage cmd
    private byte[] body;             // 已编码的业务消息体 (protobuf bytes)
    private byte   codecId;          // 编码类型: 0=protobuf, 1=json
    private long   timestamp;        // 推送生成时间

    public PushEnvelope() {}

    public PushEnvelope(String targetUserId, int cmd, byte[] body, byte codecId) {
        this.targetUserId = targetUserId;
        this.cmd = cmd;
        this.body = body;
        this.codecId = codecId;
        this.timestamp = System.currentTimeMillis();
    }

    // EventBus 上传输 JSON, body 用 Base64 编码
    public JsonObject toJson() {
        return new JsonObject()
            .put("targetUserId", targetUserId)
            .put("cmd", cmd)
            .put("body", java.util.Base64.getEncoder().encodeToString(body))
            .put("codecId", codecId)
            .put("timestamp", timestamp);
    }

    public static PushEnvelope fromJson(JsonObject json) {
        PushEnvelope env = new PushEnvelope();
        env.targetUserId = json.getString("targetUserId");
        env.cmd = json.getInteger("cmd");
        env.body = java.util.Base64.getDecoder().decode(json.getString("body"));
        env.codecId = json.getInteger("codecId").byteValue();
        env.timestamp = json.getLong("timestamp");
        return env;
    }

    // getters/setters ...
}
```

---

## 4. 生命周期状态机

```
                 ┌─────────────────────┐
                 │   未登录/离线        │
                 └──────┬──────────────┘
                        │ Client 发起 AuthReq, LoginHandler 验证通过
                        ▼
                 ┌─────────────────────┐
                 │   Login 处理         │
                 │ 1. SessionRegistry   │
                 │    .register(uid, C) │
                 │ 2. RouteService      │
                 │    .register(uid)    │←── Redis: SETEX + SADD
                 └──────┬──────────────┘
                        │
                 ┌──────▼──────────────┐
                 │   在线               │
                 │   心跳续期定时器     │────── 每 100s ──→ Redis: EXPIRE 批量续期
                 │   维持长连接         │
                 └──┬───────────┬──────┘
                    │           │
        Logout /    │           │  TCP/WS 连接断开
        Client 主动  │           │  (断网 / App 杀死 / 网络切换)
                    ▼           ▼
            ┌───────────┐ ┌───────────────┐
            │ 正常退出   │ │ 异常断连       │
            │ 1. unreg  │ │ 1. closeHandler│
            │ 2. AuthResp│ │ 2. unregister  │
            └─────┬─────┘ └───────┬───────┘
                  │               │
                  ▼               ▼
            ┌─────────────────────────┐
            │ 共同清理                 │
            │ 1. SessionRegistry      │
            │    .unregister(uid)     │
            │ 2. RouteService         │
            │    .unregister(uid)     │←── Redis: DEL + SREM
            └─────────────┬───────────┘
                          │
                          ▼
                 ┌─────────────────────┐
                 │   离线               │
                 │   Redis session 已删 │
                 │   后续消息走 PULL    │
                 └─────────────────────┘
```

---

## 5. 故障场景处理

### 5.1 Gateway 节点宕机

Gateway-1 宕机，该节点所有用户的 session 记录残留在 Redis 中（TTL 未过期）。

**影响**：Logic-Server 推送时查到 nodeId="gw-1"，EventBus.send 失败。

**自动恢复路径**：
```
层级 1: TTL 过期 (5分钟) → session 自动删除 → 后续推送直接跳过 → 用户上线后 PULL 拉取
层级 2: RENEW_SCRIPT 自带清理 → 其他 gateway 续期时发现 gw-1 的 session 已过期 → SREM
层级 3: 可选 — 幸存节点通过 MembershipListener 检测 gw-1 离开 → 主动 DEL gw-1 的所有 session
```

**层级 3 的实现（可选，推荐）**：

```java
// 在 Gateway Verticle 中注册集群成员监听
vertx.eventBus().clusterManager()  // 获取 ClusterManager
    // 通过 Hazelcast 的 MembershipListener
    .addMembershipListener(new MembershipListener() {
        @Override
        public void memberRemoved(MembershipEvent event) {
            String deadNodeId = event.getMember().getUuid().toString();
            log.warn("检测到节点离开集群: {}", deadNodeId);
            // 清理该节点的所有 session
            routeService.cleanupNodeSessions(deadNodeId);
        }
    });
```

```java
// SessionRouteService 中新增
public Future<Void> cleanupNodeSessions(String deadNodeId) {
    if (!connected) return Future.succeededFuture();

    return connection.send(cmd(EVAL)
        .arg("""
            local users = redis.call('SMEMBERS', KEYS[1])
            local prefix = ARGV[1]
            local count = 0
            for _, userId in ipairs(users) do
                redis.call('DEL', prefix .. userId)
                count = count + 1
            end
            redis.call('DEL', KEYS[1])
            return count
            """)
        .arg("1")
        .arg(SessionKeys.nodeUsersKey(deadNodeId))
        .arg(SessionKeys.SESSION_PREFIX)
    ).onSuccess(cnt -> log.info("已清理宕机节点 {} 的 {} 个 session", deadNodeId, cnt.toLong()))
     .mapEmpty();
}
```

### 5.2 Redis 不可用

| 阶段 | 影响 | 处理 |
|------|------|------|
| Login | session 无法写入 Redis | 记录 WARN 日志，允许登录但不写路由 |
| Push | Logic-Server 无法查询路由 | `RoutePushService` 自动降级为 `eventBus.publish("gateway.push", ...)` 广播 |
| Logout | session 无法删除 | 依赖 TTL 过期（Redis 恢复后自动清理） |
| 续期 | 心跳续期失败 | TTL 持续倒数，Redis 恢复前如果 TTL 过期则广播 |

降级代码已在 `RoutePushService.pushToUser()` 的 `.recoverWith()` 中实现。

### 5.3 客户端快速重连（换 Gateway）

场景：用户断网后立即重连，分配到 Gateway-2，但 Redis 中还记录着 Gateway-1。

```
时间线:
  t0: B 在 Gateway-1 在线, Redis: session:B → gw-1
  t1: B 网络断开
  t2: Gateway-1 检测到断连, closeHandler 触发
  t3: Gateway-1 调用 routeService.unregister("B")   → Redis: session:B 已删除
  t4: B 重连到 Gateway-2, Login 成功
  t5: Gateway-2 调用 routeService.register("B", connId)  → Redis: session:B → gw-2
```

**问题窗口**：t2~t3 之间如果 Logic-Server 推送消息给 B，查到 gw-1，但 B 已断连。

**处理**：
- 窗口极短（毫秒级），且 `eventBus.send("gw-1.push")` 失败时 RoutePushService 降级为广播
- 最坏情况：B 收不到这条推送 → 消息在 DB 中，B 重连后通过 PULL 拉取
- **PULL 拉取是最终兜底**

### 5.4 网络分区（脑裂）

场景：Gateway-1 与 Redis 网络不通，但 Gateway-1 的用户连接正常。

```
Gateway-1 视角：Redis 不可达 → connected=false → register/unregister 都跳过 → 降级
Logic-Server 视角：Redis 中 session:B 可能已过期 → push 跳过 → 消息走 PULL
```

**处理**：Gateway-1 仍然可以正常收发消息（本地 SessionRegistry 不受影响），但推送变成广播。对客户端 B，消息可能延迟到达（通过 PULL 拉取而非实时推送）。

---

## 6. 多设备支持

同用户多端登录时，一个 userId 对应多个 connection。

### 6.1 Redis 存储方案

```
# 方案: Hash 存储多个设备
Key:   session:{userId}
Type:  Hash
Field: deviceId or connId
Value: SessionInfo JSON

例如:
  session:B → {
    "conn_abc": {"nodeId":"gw-1","deviceType":"ios",...},
    "conn_def": {"nodeId":"gw-2","deviceType":"android",...}
  }
```

### 6.2 推送行为

C2CNotify 需要推送给 B 的**所有在线设备**。RoutePushService 遍历所有 field，向每个 nodeId 发送推送。

### 6.3 改造复杂度

当前设计（String JSON）改为 Hash 涉及：
- REGISTER_SCRIPT: `SETEX` → `HSET` + `EXPIRE`
- RENEW_SCRIPT: 遍历 Hash fields
- UNREGISTER_SCRIPT: `DEL` → `HDEL`
- RoutePushService: 返回单个 nodeId → 返回 nodeId 列表

建议 **先按单设备实现，Hash 方案预留**。在 SessionInfo 中保留 `deviceType` 字段，后续迁移成本可控。

---

## 7. 配置参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `gateway.node.id` | `gw-{random8}` | 网关节点唯一 ID，建议 K8s 部署时用 pod name |
| `gateway.session.ttl` | 300s | Redis session TTL |
| `gateway.session.renewal` | 100s | 续期间隔 (= TTL/3) |
| `gateway.push.timeout` | 5000ms | EventBus 推送超时 |
| Redis 地址 | `redis://localhost:6379` | 通过 `RedisOptions` 传入 |

---

## 8. 总结：与广播方案的对比

| 维度 | 纯广播 | Redis 路由（本文档） |
|------|--------|---------------------|
| 推送路径 | publish → 所有 gateway (N 次查表) | GET → send (1 次网络 IO + 1 次查表) |
| gateway 数量敏感度 | 高，每多一个 gateway 多一次无效查表 | 低，只发给目标 gateway |
| 运维依赖 | 无 | Redis（已有） |
| 故障降级 | 无降级概念（本身就是最简方案） | Redis 不可用时**自动降级为广播** |
| 宕机清理 | 无需清理（gateway 本地判断） | TTL + 可选主动清理 |
| 实现复杂度 | 低 | 中 |

Redis 路由方案对 10+ gateway 节点有明显收益，且 Redis 已用于 ID 生成，不是新引入的依赖。**降级广播** 消除了 "Redis 是单点" 的顾虑 —— 它不是关键路径上的硬依赖。
