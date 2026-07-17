# Session 路由方案对比：Redis vs Hazelcast

> 方案 B/B+：Logic-Server 通过集中式路由表查 userId → gatewayNodeId，精确投递推送消息，替代广播到所有 gateway。

---

## 1. 数据结构

两种方案的存储模型一致：

```
Key:   session:{userId}
Value: {
    "gatewayNodeId": "gateway-1",
    "connectionId": "abc123",
    "loginTime":    1715678900000,
    "deviceInfo":    "ios/1.0"
}
```

写入时机：login 成功后
删除时机：logout 时，或连接断开时
查询时机：Logic-Server 推送 C2CNotify / AckNotify 前

---

## 2. Redis 方案

### 2.1 架构

```
Gateway-1 (B 在线)             Logic-Server-1
    │                              │
    │ login B                      │ 收到 A 发给 B 的消息
    │ SET session:B → gateway-1    │
    │                         ┌────▼────┐
    │                         │  Redis  │
    │                         │  GET    │
    │                         │ session │
    │                         │ :B      │
    │                         └────┬────┘
    │                              │ 返回 "gateway-1"
    │   eventBus.send(             │
    │   "gateway-1.push",   ◄─────┘  精确发送到 Gateway-1
    │   c2cNotify)
    │
    │ 查本地 SessionRegistry
    │ 找到 B 的 Connection → 投递
```

### 2.2 关键实现

```java
// Gateway: login 时注册
sessionRegistry.register(userId, connection);
redis.setex("session:" + userId, 300,  // TTL 5 分钟
    JsonObject.of("nodeId", localNodeId, "connId", connId).encode());

// Gateway: 周期性续期（心跳）
vertx.setPeriodic(60_000, id ->
    redis.expire("session:" + userId, 300));

// Gateway: logout / 断连时删除
sessionRegistry.unregister(userId);
redis.del("session:" + userId);

// Logic-Server: 推送时查询
redis.get("session:" + recipientId).compose(json -> {
    if (json != null) {
        String nodeId = new JsonObject(json).getString("nodeId");
        eventBus.send(nodeId + ".push", pushEnvelope);
    }
    // json == null：用户离线，消息已持久化，后续 PULL 拉取
});
```

### 2.3 优缺点

| 优点 | 缺点 |
|------|------|
| **独立于 Vert.x 集群** — Redis 是独立服务，集群脑裂不影响路由 | **额外基础设施** — 需维护 Redis 集群，增加运维成本 |
| **成熟稳定** — Redis 是最成熟的数据存储之一，工具链完善 | **数据一致性问题** — session 数据和连接状态可能不一致（节点宕机时） |
| **已有依赖** — `vertx-redis-client` 已引入，`RedisIdGenerator` 已在使用 | **需要 TTL + 续期** — 必须周期性刷新过期时间，增加 Redis 写负载 |
| **可存储丰富数据** — 设备信息、登录 IP、最后活跃时间等 | **TTL 是双刃剑** — 设置太短可能误判离线，太长则残留脏数据 |
| **可视化/监控** — Redis 有成熟的监控方案 | |

### 2.4 故障场景处理

| 场景 | 问题 | 解法 |
|------|------|------|
| Gateway 宕机 | B 的连接断开，Redis 中 session 仍在（TTL 未过期） | ① TTL 尽量短（如 2 分钟）+ 心跳续期 ② 消息发送失败时 logic-server 标记离线 |
| Redis 不可用 | 无法查询路由，推送失败 | 降级为**广播模式**（publish 到所有 gateway） |
| TTL 误过期 | 用户实际在线但 session 记录已消失 | 降级为广播，或 gateway 检测到后重新写入 |
| 客户端快速重连 | 旧 gateway 残留脏 session | login 时覆盖旧值（SET 语义） |

---

## 3. Hazelcast 方案

### 3.1 架构

```
Gateway-1 (B 在线)           Logic-Server-1
    │                            │
    │ login B                    │ 收到 A 发给 B 的消息
    │ hazelcastMap.put           │
    │  ("B", "gateway-1")        │
    │                       ┌────▼──────────┐
    │                       │  Hazelcast    │
    │                       │  IMap.get(B)  │
    │                       └────┬──────────┘
    │                            │ 返回 "gateway-1"
    │   eventBus.send(           │
    │   "gateway-1.push", ◄─────┘  精确发送到 Gateway-1
    │   c2cNotify)
    │
    │ 查本地 SessionRegistry
    │ 找到 B 的 Connection → 投递
```

### 3.2 关键实现

```java
// 共享的分布式 Map
// 放在 pomelo-common 中，所有节点共享同一个 IMap 实例
IMap<String, SessionInfo> sessionRouteMap =
    hazelcastInstance.getMap("session-routes");

// Gateway: login 时写入
sessionRegistry.register(userId, connection);
sessionRouteMap.put(userId,
    new SessionInfo(localNodeId, connId, System.currentTimeMillis()),
    300, TimeUnit.SECONDS);  // 5 分钟 TTL（Hazelcast 原生支持）

// Gateway: logout / 断连时删除
sessionRegistry.unregister(userId);
sessionRouteMap.remove(userId);

// Logic-Server: 推送时查询
SessionInfo info = sessionRouteMap.get(recipientId);
if (info != null) {
    eventBus.send(info.getNodeId() + ".push", pushEnvelope);
} else {
    // 用户离线
}
```

### 3.3 优缺点

| 优点 | 缺点 |
|------|------|
| **零额外依赖** — `vertx-hazelcast` 已在项目中，EventBus 集群已依赖 Hazelcast | **耦合集群** — Hazelcast 集群故障同时影响 EventBus 和 Session 路由 |
| **事件驱动清理** — Hazelcast EntryListener 可在节点离开集群时自动清理其 session | **共享存储** — IMap 占用堆内存，与 EventBus 共享 JVM 资源 |
| **原生 TTL** — IMap 支持 per-entry TTL，无需手动续期逻辑 | **调试困难** — 分布式 Map 的数据不一致排查比 Redis 复杂 |
| **JVM 原生对象** — 直接存取 Java 对象，无需序列化为 JSON | **脑裂风险** — 网络分区时可能出现多主写入，旧数据覆盖新数据 |
| **无运维成本** — 不需要额外部署 Redis 集群 | **监控缺失** — 没有 Redis 那样成熟的可视化和监控生态 |

### 3.4 故障场景处理

| 场景 | 问题 | 解法 |
|------|------|------|
| Gateway 宕机 | Hazelcast 自动检测到成员离开，EntryListener 清理其 session | 利用 `membershipListener` + `entryListener` 自动清理 |
| Hazelcast 脑裂 | 两个分区各自写入同一 userId | 配置 `split-brain-protection` 合并策略：`LATEST_UPDATE` 或 `DISCARD` |
| 集群重启 | 所有 session 丢失 | 用户重连后重新写入；未写入期间降级为广播 |
| OOM | IMap 过大挤占堆内存 | 配置 `max-size` + 驱逐策略；或配置为 `NATIVE` 内存模式（Hazelcast 企业版） |

### 3.5 Member 离开自动清理

这是 Hazelcast 方案的关键优势：

```java
// 监听集群成员离开，自动删除该节点关联的所有 session
hazelcastInstance.getCluster().addMembershipListener(new MembershipListener() {
    @Override
    public void memberRemoved(MembershipEvent event) {
        String deadNodeId = event.getMember().getUuid().toString();
        // 清理该节点上的所有 session 记录
        sessionRouteMap.removeAll(
            Predicates.equal("nodeId", deadNodeId)
        );
        log.info("节点 {} 离开集群，已清理其 session 路由", deadNodeId);
    }
});
```

Redis 方案需要额外的健康检查或依赖 TTL 过期，无法做到即时清理。

---

## 4. 综合对比

| 维度 | Redis | Hazelcast |
|------|-------|-----------|
| **初始成本** | 需部署 Redis（已有部分使用） | 零 |
| **运维复杂度** | 需要维护两套集群（Hazelcast + Redis） | 只需维护 Hazelcast 集群 |
| **故障隔离** | Redis 故障不影响 EventBus 通讯 | 集群故障同时影响路由和通讯 |
| **数据可靠性** | Redis 可配置持久化（RDB/AOF） | Hazelcast 默认内存存储，重启丢失 |
| **TTL 实现** | `SETEX` + 心跳续期 | 原生 `put(key, value, ttl, unit)` |
| **宕机清理** | 依赖 TTL 过期（被动），或额外心跳检测 | `MembershipListener` 即时主动清理 |
| **查询延迟** | 网络 IO（~1ms 同机房） | 通常本地缓存，接近零延迟（取决于备份策略） |
| **数据容量** | 独立内存，不受 JVM 堆限制 | 与 EventBus 共享 JVM 堆，大集群需关注 OOM |
| **监控** | RedisInsight、Prometheus exporter 等 | Hazelcast Management Center（社区版功能有限） |
| **降级能力** | 可降级为广播 | 同左 |

---

## 5. 关键决策点

### 选 Redis 如果：

- 项目已经有 Redis 集群在生产环境运行
- 需要 session 数据持久化（服务重启不丢失在线状态）
- 需要存储大量 session 元数据（设备信息、client IP 等）
- 运维团队对 Redis 更熟悉
- 希望将路由数据和集群通讯解耦

### 选 Hazelcast 如果：

- 希望尽量减少运维组件数量
- 可以接受 session 数据随集群重启而丢失
- 重视宕机即时清理（不如等 TTL 过期）
- 节点数量不多（几十台），session IMap 不会太大
- 追求更低查询延迟

---

## 6. 建议：Redis + 广播降级

对于本项目，推荐 **Redis 为主，广播为降级** 的混合方案：

```java
public class SessionRouteService {

    private final Redis redis;
    private final EventBus eventBus;
    private final IMap<String, String> fallbackMap; // Hazelcast 备用

    public Future<Void> pushToUser(String userId, PushEnvelope env) {
        // 1. 优先查 Redis
        return redis.get("session:" + userId).compose(json -> {
            if (json != null) {
                String nodeId = new JsonObject(json).getString("nodeId");
                return eventBus.request(nodeId + ".push", env);
            }
            return Future.failedFuture("user offline");
        }).recoverWith(err -> {
            // 2. Redis 不可用 → 降级为广播
            log.warn("Redis 不可用，降级为广播推送: userId={}", userId);
            eventBus.publish("gateway.push", env.withTargetUserId(userId));
            return Future.succeededFuture();
        });
    }
}
```

**理由**：
- Redis 已经在项目中用于 ID 生成（`RedisIdGenerator`），是已有组件
- 广播降级保证 Redis 故障时服务不中断
- 后续可按需要切换到 Hazelcast（从一个中心化存储到另一个，迁移成本低）
- 预留了灵活度：如果未来发现 Redis 成为瓶颈，可平滑切换到 Hazelcast 分布式 Map

这个混合方案将 Redis 视为性能优化（减少广播量），而非关键路径依赖 — 这是务实的分层降级策略。
