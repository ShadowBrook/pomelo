# 网关软负载均衡设计

**日期**: 2026-07-28
**状态**: 设计完成，待实现

## 背景

分布式部署后，多个网关节点（WebSocket Gateway）各自独立运行在不同的 IP/端口上。客户端（Web 前端）登录后需要知道连接哪个网关实例。目前没有客户端侧网关发现机制——客户端只能硬编码或人工配置一个网关地址，无法实现负载均衡。

本设计提供一套完整的**客户端侧软负载**方案：认证成功后，客户端通过 HTTP API 获取一个最优的网关节点地址，然后连接该节点。

## 设计决策

| 维度 | 选择 | 理由 |
|------|------|------|
| 连接流程 | 两请求：login → discover → connect | 职责分离，discover 可按需重调用（重连、切换网关） |
| 负载策略 | 最小连接数 | 适合 IM 长连接场景，避免连接数不均 |
| 注册中心 | Vert.x AsyncMap（集群无关） | 标准 SPI，Hazelcast/Infinispan/Redis 均可用 |
| 原子更新 | CAS 循环 (`replaceIfPresent`) + 心跳校正 | AsyncMap 标准 API，可跨所有 ClusterManager 移植 |
| 死节点清理 | TTL 自动过期（30s） | 无需手动过滤，宕机节点自动从注册表消失 |
| 网关类型 | 仅 WebSocket（9001） | 先聚焦 Web 前端，TCP 后续扩展 |

## 可移植性

经过对 `vertx-redis-clustermanager`（基于 Redisson 的 Redis ClusterManager 实现）的调研，确认所有主流 Vert.x ClusterManager 都支持 `AsyncMap` 的以下原子操作：

| 操作 | Hazelcast | Infinispan | Redis (Redisson) |
|------|-----------|------------|------------------|
| `putIfAbsent` | ✅ | ✅ | ✅ |
| `replaceIfPresent(old, new)` | ✅ | ✅ | ✅ (Lua EVAL) |
| `put(k, v, ttl)` | ✅ | ✅ | ✅ |

因此 GatewayRegistry 完全基于标准 `AsyncMap` API 实现，**零依赖特定 ClusterManager**。

## 整体架构

```
┌──────────┐     ┌──────────────┐     ┌──────────────────┐
│  Client  │     │ Logic Server │     │  Gateway Node 1  │
│ (Web)    │     │              │     │  ip: 10.0.1.5     │
└────┬─────┘     └──────┬───────┘     │  wsPort: 9001     │
     │                  │             │  connections: 342  │
     │ 1. POST /api/user/login        └────────┬───────────┘
     │─────────────────>│                      │
     │<─ {token, ...} ──│                      │
     │                  │                      │
     │ 2. GET /api/gateway/discover            │
     │─────────────────>│                      │
     │                  │ 读 AsyncMap           │
     │                  │ "__pomelo.gateway-    │
     │                  │  nodes"               │
     │                  │ 选最小 connections    │
     │<─ {host, wsPort}─│                      │
     │                  │                      │
     │ 3. WS connect to 10.0.1.5:9001 ────────>│
     │ 4. AuthReq {token} ─────────────────────>│
     │<─ AuthResp ──────────────────────────────│
     │                  │                      │
     │ 5. Gateway CAS +1 connections           │
     │                  │                      │
```

**不改动的部分**：AuthReq/AuthResp 流程、TokenService、JWT 校验、SessionRouteTable、PushRouter。

## 数据模型

### AsyncMap

```
Map Name:  "__pomelo.gateway-nodes"
Key:       nodeId (String, 8 位 UUID 前缀，与 SessionRouteTable 共用)
Value:     JSON String
TTL:       30 秒（心跳每 10 秒续期）
```

```json
{
  "nodeId": "a1b2c3d4",
  "host": "10.0.1.5",
  "wsPort": 9001,
  "connections": 342,
  "startTime": 1753804800000,
  "lastHeartbeat": 1753804850000
}
```

### 生命周期

| 事件 | 操作 | 原子性 |
|------|------|--------|
| Gateway 启动 | `putIfAbsent(nodeId, json, 30_000)` 注册 | ✅ 原子 |
| 用户连接 | CAS 循环 `connections + 1` | ✅ 原子（乐观锁重试） |
| 用户断开 | CAS 循环 `connections - 1` | ✅ 原子（乐观锁重试） |
| 定时心跳（10s） | CAS 循环：更新 `lastHeartbeat` + 校正 `connections` + 续期 TTL | ✅ 原子（乐观锁重试） |
| Gateway 停止 | `remove(nodeId)` 注销 | ✅ 原子 |
| 节点宕机 | TTL 30s 后自动过期 | ✅ Redis/Hazelcast 原生支持 |

### CAS 循环原理

```java
// incrementConnections / decrementConnections / heartbeat 统一模式
boolean success = false;
int retries = 0;
while (!success && retries < MAX_RETRIES) {
    String oldJson = map.get(nodeId);             // 1. 读当前值
    JsonObject newJson = modify(oldJson);          // 2. 修改
    success = map.replaceIfPresent(nodeId, oldJson, newJson);  // 3. CAS 写入
    retries++;
}
```

`replaceIfPresent` 保证：仅当 key 的当前值等于 `oldJson` 时才替换——基于**值相等**的乐观锁。冲突时重试即可。心跳每 10s 全量校正，即使 CAS 短期失败也会被修复。

## API 设计

### GET /api/gateway/discover

返回连接数最少的可用 WebSocket 网关。

**Response 200**:
```json
{
  "code": 0,
  "nodeId": "a1b2c3d4",
  "host": "10.0.1.5",
  "wsPort": 9001
}
```

**Response 503** (无可用网关):
```json
{
  "code": 503,
  "message": "No available gateway"
}
```

**选择逻辑**：
1. 读 AsyncMap 所有 `values()`——TTL 已自动清理死节点，无需过滤
2. 按 `connections` 升序排列
3. 返回第一条（最小连接数）

## 新增组件：GatewayRegistry

### 接口

```java
public class GatewayRegistry {
    // 网关注册（启动时调用一次，带 TTL）
    Future<Void> register(String host, int wsPort);

    // 连接数 +1（CAS 循环）
    Future<Void> incrementConnections();

    // 连接数 -1（CAS 循环）
    Future<Void> decrementConnections();

    // 心跳：更新 lastHeartbeat + 校正 connections + 续期 TTL
    Future<Void> heartbeat(int currentConnections);

    // 注销（停止时调用）
    Future<Void> unregister();

    // 静态：查询所有存活网关（ApiVerticle 调用）
    static Future<List<GatewayNode>> listAll(Vertx vertx);
}
```

### 与现有组件的关系

```
SessionRegistry  →  只管 Connection ↔ userId 映射（不变）
GatewayRegistry  →  只管 node 注册 + 连接计数（新增，纯 AsyncMap API）
MessageDispatcher →  协调两者 + SessionRouteTable（新增 GatewayRegistry 调用）
```

## 配置

```yaml
# conf/config.yaml 新增
gateway:
  advertised:
    host: ""  # 空 = 自动检测本机 IP，容器环境通过 POMELO_GATEWAY_ADVERTISED_HOST 注入
```

## 需改动的文件

| 类型 | 文件 | 改动内容 |
|------|------|----------|
| **新建** | `pomelo-common/.../config/GatewayRegistry.java` | 网关注册与发现核心类，纯 AsyncMap CAS 循环 |
| **新建** | `pomelo-common/.../config/GatewayNode.java` | 网关节点数据 record |
| **修改** | `pomelo-common/.../config/SessionRouteTable.java` | 新增 `nodeId` 参数构造器，共享 nodeId |
| **修改** | `pomelo-gateway/.../handler/MessageDispatcher.java` | 注入 GatewayRegistry，login/断开时更新连接数 |
| **修改** | `pomelo-gateway/.../handler/SessionRegistry.java` | 新增 `sessionCount()` 方法 |
| **修改** | `pomelo-gateway/.../gateway/WsGatewayVerticle.java` | 启动注册 + 心跳定时器 + 停止注销 + 断开减数 |
| **修改** | `pomelo-logic-server/.../logic/ApiVerticle.java` | 新增 `GET /api/gateway/discover` |
| **修改** | `conf/config.yaml` | 新增 `gateway.advertised.host` |

## 错误与边界处理

| 场景 | 处理方式 |
|------|----------|
| 无可用网关 | discover 返回 503，客户端重试或提示用户 |
| 网关宕机 | 心跳停止 → TTL 30s 后自动过期 → discover 不再返回 |
| CAS 重试耗尽 | 记录 WARN 日志，不阻塞主流程；下次心跳（10s 内）自动修正 |
| 网关优雅停止 | `stop()` 先 `unregister()` 再关 `wsServer` |
| 并发 discover | 纯读操作，无锁，无副作用 |
| 容器 IP 变化 | `POMELO_GATEWAY_ADVERTISED_HOST` 环境变量注入 |
| 集群管理器切换 | 零代码改动——纯 AsyncMap API 在所有 CM 上行为一致 |

## 客户端重连流程

```
客户端感知连接断开
  → GET /api/gateway/discover
  → 获取新网关 host:port
  → 连接新网关
  → AuthReq {token}
  → 恢复正常
```

## 验证方式

1. **单元测试**：GatewayNode 序列化、GatewayRegistry CAS 循环重试逻辑（mock AsyncMap）
2. **集成测试**：启动 2 个网关 + logic server → discover 返回最少连接数节点 → 连接客户端验证计数更新
3. **TTL 测试**：手动注册节点 → 停止心跳 → 30s 后节点自动过期
4. **宕机测试**：kill 一个网关 → 30s 内 discover 不再返回该节点
5. **跨 CM 测试**（可选）：切换为 vertx-redis-clustermanager → 验证所有行为一致
