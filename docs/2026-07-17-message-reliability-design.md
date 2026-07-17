# 消息可靠性方案设计

> 日期：2026-07-17
> 状态：已确认
> 范围：服务端 + 客户端 SDK（JavaScript）

## 一、目标

实现 IM 单聊（C2C）消息的端到端可靠送达，包含三层确认机制和离线消息支持。

## 二、整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        客户端 A (浏览器)                          │
│  ┌──────────┐   ┌──────────┐   ┌──────────┐   ┌─────────────┐  │
│  │ 发送队列   │──▶│ 重试控制器 │──▶│ WebSocket│──▶│ ImSDK 对外API│  │
│  │ (内存队列) │   │(指数退避) │   │ 连接管理  │   │ sendMessage() │  │
│  └──────────┘   └──────────┘   └──────────┘   │ onMessage()   │  │
│                                                │ onAck()       │  │
│                                                └─────────────┘  │
└─────────────────────────────────────────────────────────────────┘
         │ ① C2CReq (含雪花ID)            ▲ ③ C2CResp / ⑤⑦ AckNotify
         ▼                                │
┌─────────────────────────────────────────────────────────────────┐
│                         Pomelo 服务端                             │
│                                                                   │
│  MessageDispatcher ──▶ C2CMessageHandler                         │
│                            │ ② 写DB (status=0)                    │
│                            │ ② 查SessionRegistry                 │
│                            │ ② B在线→推Notify / B离线→跳过        │
│                            ▼                                      │
│                        AckReqHandler                              │
│                            │ ④⑥ 更新DB状态                         │
│                            │ ⑤⑦ 推送AckNotify给发送者              │
│                            ▼                                      │
│  ┌────────────┐  ┌──────────────┐  ┌──────────────────┐         │
│  │SessionRegisty│  │MessageRepo   │  │  RedisIdGenerator│         │
│  │ userId→Conn │  │ PG持久化/查询 │  │  seq全局ID生成    │         │
│  └────────────┘  └──────────────┘  └──────────────────┘         │
└─────────────────────────────────────────────────────────────────┘
         │ ② C2CNotify (B在线时)          ▲ ④ AckReq (RECEIVED/SEEN)
         ▼                                │
┌─────────────────────────────────────────────────────────────────┐
│                        客户端 B (浏览器)                          │
│  ┌─────────────┐   ┌──────────┐   ┌──────────┐                  │
│  │ ImSDK 对外API│   │ ACK自动机 │   │ Pull拉取  │                  │
│  │ onMessage()  │──▶│ 收到即回   │   │ 上线拉取  │                  │
│  │ markSeen()   │   │ RECEIVED  │   │ 离线消息  │                  │
│  └─────────────┘   └──────────┘   └──────────┘                  │
└─────────────────────────────────────────────────────────────────┘
```

## 三、三层确认模型

```
客户端A ──① C2CReq──▶ 服务端 ──② C2CNotify──▶ 客户端B
   ◀──③ C2CResp──                ◀──④ AckReq(RECEIVED)──
   ◀────────────────⑤ AckNotify(RECEIVED)────────────────
                                       ◀──⑥ AckReq(SEEN)──
   ◀────────────────⑦ AckNotify(SEEN)──────────────────
```

| 阶段 | 含义 | DB status | A 看到 | B 看到 |
|------|------|-----------|--------|--------|
| ①→③ | 服务端已收到并存储 | 0 | "已发送" ✓ | — |
| ④→⑤ | B 设备已收到消息 | 1 | "已送达" ✓✓ | 收到新消息 |
| ⑥→⑦ | B 已阅读消息 | 2 | "已读" ✓✓(蓝) | "已读" |

## 四、消息生命周期状态机

```
客户端A:  [排队中] → [发送中] → [已发送] → [已送达] → [已读]
              │           │          │          │         │
服务端DB:     ✗          ✗     status=0   status=1  status=2
              │           │          │          │         │
触发条件:  入队      WS发送中   收到C2CResp  B的RECEIVED B的SEEN
```

## 五、服务端设计

### 5.1 C2CMessageHandler 流程

```
C2CReq 到达
  │
  ├─ 1. 解码消息体（Protobuf/JSON）
  ├─ 2. 校验：sender 已登录？recipient 存在？
  ├─ 3. idGenerator.nextId() → 生成 seq
  ├─ 4. messageRepo.save(record) → 持久化（status=0）
  ├─ 5. 构建 C2CResp → 返回给发送者 A
  │
  └─ 6. 异步推送（不阻塞响应）：
        sessionRegistry.getConnection(recipientId)
        ├─ 在线 → connection.write(C2CNotify.encodeToWire())
        └─ 离线 → 跳过（消息已在 DB，等 B 上线 Pull）
```

### 5.2 AckReqHandler 流程

```
AckReq 到达
  │
  ├─ 1. 解码 messageIds[] + ackType (RECEIVED=0 / SEEN=1)
  ├─ 2. batchUpdateStatus(messageIds, newStatus)
  │       RECEIVED → status=1
  │       SEEN     → status=2
  │
  ├─ 3. 查询每条消息的 senderId
  │
  ├─ 4. 对每个 senderId 聚合消息列表
  │      sessionRegistry.getConnection(senderId)
  │      ├─ 在线 → 推送 AckNotify
  │      └─ 离线 → 跳过（发送者上线后可通过 Pull 查看状态）
  │
  └─ 5. 返回 AckResp 给确认者
```

### 5.3 新增接口层

**MessageService（业务逻辑层）**

```java
public interface MessageService {
  Future<C2CRespResult> sendC2CMessage(C2CReqContext ctx);
  Future<List<AckNotifyContext>> processAck(List<Long> messageIds, AckType type, String ackFromUserId);
  Future<List<MessageRecord>> pullOfflineMessages(String userId, long sinceSeq, int limit);
}
```

**MessageRepository（数据层）**

```java
public interface MessageRepository {
  Future<Void> save(MessageRecord record);
  Future<Void> updateStatus(long messageId, int newStatus);
  Future<Void> batchUpdateStatus(List<Long> messageIds, int newStatus);
  Future<List<MessageRecord>> pullPending(String userId, long sinceSeq, int limit);
  Future<MessageRecord> findById(long messageId);
}
```

### 5.4 DB 变更

消息表保持现有 `im_message_c2c` 不变，新增一个部分索引：

```sql
-- 部分索引：仅索引未完全送达的消息
CREATE INDEX CONCURRENTLY idx_c2c_pending
  ON im_message_c2c (recipient_id, created_at)
  WHERE status < 2;
```

status 语义：

| status | 含义 | 触发 |
|--------|------|------|
| 0 | 已发送（服务端已存） | C2CMessageHandler 写入 |
| 1 | 已送达（接收方设备收到） | AckReqHandler 处理 RECEIVED |
| 2 | 已读（接收方已查看） | AckReqHandler 处理 SEEN |

> **决策：单表 + status 更新，不用独立离线表。**
> 理由：已有 status 字段 + 部分索引性能等价独立离线表，且避免了双表事务和状态同步的工程复杂度。

### 5.5 文件改动清单

| 文件 | 改动 |
|------|------|
| `MessageRepository.java` | 抽取接口，新增 `updateStatus`、`batchUpdateStatus`、`findById` |
| `C2CMessageHandler.java` | 重写：完整 send→save→resp→push 链路 |
| `AckReqHandler.java` | 重写：batchUpdate + 查 senderId + 推送 AckNotify |
| `PullMessageHandler.java` | 微调：使用 `pullPending` 替代 `pullOfflineMessages` |
| `LoginHandler.java` | 新增：登录后注册 SessionRegistry |
| `AbstractMessageHandler.java` | 扩展：注入 `MessageService`、`SessionRegistry` 等 |
| `db/schema.sql` | 新增部分索引 `idx_c2c_pending` |

## 六、客户端 SDK 设计

### 6.1 模块结构

```
ImSDK
├── ConnectionManager    ← WebSocket 生命周期、断线重连、心跳
├── SendQueue            ← 发送队列、超时重试（指数退避）、消息状态管理
├── AckAutomaton         ← 收到消息自动回 RECEIVED，markSeen 回 SEEN
├── PullManager          ← 重连后自动拉取离线消息
└── ImClient (对外 API)  ← sendMessage / onMessage / onAck / markSeen / connect
```

### 6.2 发送队列 + 重试策略

- 消息先入内存队列，立即尝试发送
- 超时时间：5s → 10s → 20s（指数退避，最多 3 次）
- `messageId` 由客户端生成（雪花ID），重试时不变，服务端幂等
- 3 次重试耗尽 → status='failed'，UI 显示红色感叹号 + 允许手动重发

### 6.3 消息状态与 UI 展示

| status | UI 显示 | 触发 |
|--------|---------|------|
| `pending` | 时钟图标 | 消息入队 |
| `sending` | 转圈 | WebSocket 发送中 |
| `sent` | 单勾 ✓ | 收到 C2CResp |
| `delivered` | 双勾 ✓✓ | 收到 AckNotify(RECEIVED) |
| `seen` | 双勾蓝 ✓✓ | 收到 AckNotify(SEEN) |
| `failed` | 红色感叹号 + 可点击重发 | 3 次重试耗尽 |

### 6.4 ACK 自动机

- 收到 C2CNotify → 自动排队回 RECEIVED
- 200ms 批量聚合窗口（多条消息合并为一个 AckReq）
- 用户调用 `markSeen([ids])` → 发送 SEEN

### 6.5 连接管理与心跳

- 30s Ping/Pong 心跳
- 连续 3 次 Pong 超时 → 断开重连
- 重连后：自动 Pull 离线消息 + 重发 pending 队列

### 6.6 对外 API

```javascript
const sdk = new ImSDK({ url: 'ws://localhost:9001/ws' });

await sdk.connect({ userId: 'userA', token: 'xxx' });

const msgId = sdk.sendMessage({
  recipientId: 'userB',
  msgType: MsgType.TEXT,
  content: 'Hello!'
});

sdk.onMessage((msg) => { /* 新消息回调，SDK 已自动回 RECEIVED */ });
sdk.onStatusChange((msg) => { /* 消息状态变化：pending→sent→delivered→seen→failed */ });
sdk.markSeen([msgId1, msgId2]);  // 标记已读
```

### 6.7 文件结构

```
src/test/resources/
├── im-sdk.js          ← SDK 主体
└── c2c-test.html      ← 已有测试页面，对接新 SDK API
```

## 七、幂等保证

| 场景 | 处理方式 |
|------|---------|
| 客户端重复发送（同 messageId） | 服务端 INSERT 使用 `ON CONFLICT (id) DO NOTHING` |
| 重连后重发 pending | messageId 不变，服务端幂等忽略 |
| 重复 ACK | batchUpdateStatus 使用 `WHERE status < newStatus` 防止降级 |
| B 重复拉取离线 | PullReq 携带 `sinceSeq`，增量拉取天然去重 |

## 八、后续架构演进：四层拆分

当前阶段在代码层面做好逻辑分层，后续按需独立部署：

```
Gateway → Router → Logic → Data
```

| 阶段 | 动作 | 触发条件 |
|------|------|---------|
| **当前** | 定义 `MessageService` + `MessageRepository` 接口，同进程直连实现 | 项目早期 |
| **第一步** | Data 层独立 — DataServer Verticle，EventBus 通信 | DB 连接池需独立扩展 |
| **第二步** | Router 独立 — Session 信息迁 Redis，独立路由服务 | 多 Gateway 实例互相发现 |
| **第三步** | Logic 独立 — LogicServer 从 Gateway 拆出 | 业务逻辑需独立扩缩容 |

## 九、未涉及的范围

- 群聊（C2G）消息可靠性 — 机制类似但需处理多接收者 ACK 聚合
- 客户端 IndexedDB 持久化队列 — 当前用内存队列，进程重启会丢未发送消息
- 多媒体消息的上传/下载 — 当前只处理文本内容
- 端到端加密 — 当前明文传输
