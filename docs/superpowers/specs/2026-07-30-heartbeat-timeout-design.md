# 心跳超时踢除用户 Session 设计

**日期**: 2026-07-30
**状态**: 设计完成，待实现

## 背景

用户连接网关后，如果长时间不发送 PING 心跳（网络断开、App 切后台被系统杀死、网络切换等），服务端需要主动踢除该用户的 session，释放连接和路由表资源。当前 `HeartbeatService` 只回 Pong，不追踪超时。

## 设计决策

| 维度 | 选择 | 理由 |
|------|------|------|
| 定时器方式 | per-session 一次性定时器（`vertx.setTimer`） | 每个 session 独立倒计时，无全局扫描开销 |
| 心跳追踪 | 网关侧拦截（`MessageDispatcher.dispatch` 中检测 `CMD_PING_VALUE`） | 不改变 EventBus 通信路径，实现简单 |
| 超时阈值 | 90 秒（可配置） | 客户端 30s 发一次 PING，容忍 2 次丢包 |
| 踢除范围 | TCP + WebSocket 双网关 | 统一处理 |
| 定时器回调 | 只关 `Connection`，清理由已有的 `closeHandler` 完成 | 避免重复代码，复用已有清理链 |
| 线程安全 | `AtomicLong` 包装 timer ID | 确保 cancel/reset 的幂等性和线程安全 |

## 定时器生命周期

```
连接成功(AuthReq) → startHeartbeatTimer(90s) → 倒计时开始
收到 PING        → resetHeartbeatTimer()     → 取消旧 + 创建新(90s)
正常断开          → cancelHeartbeatTimer()   → 取消
定时器触发        → connection.close()        → closeHandler 做清理
```

### 为什么定时器回调只关连接

已有的 `closeHandler` 已经做了完整的清理链：

```
connection.close()
  → ws.closeHandler / socket.closeHandler 触发
    → sessionRegistry.unregisterByConnection(conn)
    → routeTable.unregister(userId)
    → gatewayRegistry.decrementConnections()
```

定时器回调只调用 `connection.close()`，让已有的清理逻辑自然触发，零重复。

## 与 lemon 模式的适配

参考项目：`/Users/user/code/lemon/lemon-chat/src/main/java/com/lemon/chat/infrastructure/manager/SessionManager.java`

| lemon | pomelo 适配 |
|---|---|
| `SessionEntry` 持有 `AtomicLong timerId` | `Session` 类加 `AtomicLong heartbeatTimerId` |
| session 在 WebSocket 握手时创建 | session 在 AuthReq 成功后创建（`handleSessionUpdates`） |
| `closeSession()` 直接关 ws + 删 map | 定时器回调只关 `Connection`，清理由 `closeHandler` 完成 |

## 数据模型

### Session 新增字段

```java
static class Session {
    final long id;
    final String userId;
    final Connection connection;
    final byte codecId;
    final String userName;
    final String nickname;
    final String token;
    final AtomicLong heartbeatTimerId;  // Vert.x timer ID, 初始值 -1, -1 表示无定时器
}
```

### SessionRegistry 新增方法

```java
// 启动心跳定时器（login 成功后调用）
void startHeartbeatTimer(Vertx vertx, String userId, long timeoutMs);

// 重置心跳定时器（收到 PING 时调用）
void resetHeartbeatTimer(Vertx vertx, String userId, long timeoutMs);

// 取消心跳定时器（断开时调用）
void cancelHeartbeatTimer(String userId);
```

**实现细节**（参照 lemon）：

```java
void startHeartbeatTimer(Vertx vertx, String userId, long timeoutMs) {
    Session session = sessions.get(userId);
    if (session == null) return;
    long timerId = vertx.setTimer(timeoutMs, id -> {
        log.warn("用户 {} 心跳超时 {}ms，断开连接", userId, timeoutMs);
        session.connection.close();
    });
    session.heartbeatTimerId.set(timerId);
}

void resetHeartbeatTimer(Vertx vertx, String userId, long timeoutMs) {
    Session session = sessions.get(userId);
    if (session == null) return;
    long oldId = session.heartbeatTimerId.getAndSet(-1);
    if (oldId >= 0) vertx.cancelTimer(oldId);
    long newId = vertx.setTimer(timeoutMs, id -> {
        log.warn("用户 {} 心跳超时 {}ms，断开连接", userId, timeoutMs);
        session.connection.close();
    });
    session.heartbeatTimerId.set(newId);
}

void cancelHeartbeatTimer(String userId) {
    Session session = sessions.get(userId);
    if (session == null) return;
    long oldId = session.heartbeatTimerId.getAndSet(-1);
    if (oldId >= 0) vertx.cancelTimer(oldId);
}
```

`getAndSet(-1)` 保证幂等：timer 已经触发或已被取消后，后续 cancel 操作拿到 -1，不会重复取消。

### MessageDispatcher 改动

- `handleSessionUpdates()`：login 成功后调用 `sessionRegistry.startHeartbeatTimer()`
- `dispatch()`：`cmd == CMD_PING_VALUE` 时提取 userId，调用 `sessionRegistry.resetHeartbeatTimer()`

## 配置

```yaml
# conf/config.yaml
gateway:
  heartbeat:
    timeoutMs: 90000  # 90 秒
```

## 需改动的文件

| 类型 | 文件 | 改动内容 |
|------|------|----------|
| 修改 | `SessionRegistry.java` | Session 加 `AtomicLong heartbeatTimerId`；新增 `start/reset/cancelHeartbeatTimer`；unregister 时取消定时器 |
| 修改 | `MessageDispatcher.java` | `handleSessionUpdates` 中 login 时启动定时器；`dispatch` 中拦截 PING 重置定时器 |
| 修改 | `TcpGatewayVerticle.java` | 修复 closeHandler bug：补充 `routeTable.unregister(userId)` |
| 修改 | `conf/config.yaml` | 新增 `gateway.heartbeat.timeoutMs` |

## 不改动

- `HeartbeatService.java`：保持纯 Pong 回显，不参与心跳追踪
- `WsGatewayVerticle` / `TcpGatewayVerticle` 的 closeHandler/exceptionHandler：不变，定时器只关连接
- `AuthService`、`LogicVerticle`：不变

## 验证方式

1. **单元测试**：`SessionRegistry` 的 `start/reset/cancelHeartbeatTimer`（mock Vertx）
2. **集成测试**：启动网关 → AuthReq → 停止发 PING → 90s 后连接关闭、session 清除
