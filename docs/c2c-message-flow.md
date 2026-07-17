# C2C 单聊消息收发流程

## 参与者

| 角色 | 说明 |
|------|------|
| **用户 A** | 消息发送方 |
| **Pomelo 服务端** | IM 消息服务，负责存储、转发、确认管理 |
| **用户 B** | 消息接收方 |

## 协议消息

| Cmd | 方向 | 说明 |
|-----|------|------|
| `C2C_REQ` (0x0010) | C→S | 发送单聊消息请求 |
| `C2C_RESP` (0x0011) | S→C | 单聊消息发送响应（含 messageId、seq） |
| `C2C_NOTIFY` (0x0012) | S→C | 单聊消息推送（推送给接收方） |
| `ACK_REQ` (0x0052) | C→S | 消息确认请求（含 messageIds、ackType） |
| `ACK_RESP` (0x0053) | S→C | 消息确认响应 |
| `ACK_NOTIFY` (0x0054) | S→C | 消息确认推送（通知发送方消息已被接收/已读） |

## 完整流程

```mermaid
sequenceDiagram
    participant A as 用户 A<br>(发送方)
    participant S as Pomelo 服务端
    participant B as 用户 B<br>(接收方)

    Note over A,B: 阶段一：发送消息

    A->>S: C2CReq (0x0010)<br>sender_id=A, recipient_id=B<br>message: {msgType, content, timestamp}

    Note over S: 1. 校验消息合法性<br>2. 生成 messageId + seq<br>3. 持久化存储消息<br>4. 查询 B 是否在线

    S-->>A: C2CResp (0x0011)<br>code=0, message_id, server_time, seq<br>← 发送方收到"消息已送达服务器"

    Note over A,B: 阶段二：推送消息给接收方

    S->>B: C2CNotify (0x0012)<br>sender_id=A, recipient_id=B<br>message: {msgType, content, timestamp}<br>seq<br>← 接收方收到新消息

    Note over A,B: 阶段三：消息已收到确认

    B->>S: AckReq (0x0052)<br>message_ids=[msgId1, msgId2, ...]<br>ack_type = RECEIVED (0)<br>← 接收方确认"消息已收到"

    Note over S: 1. 更新消息状态为"已收到"<br>2. 持久化 ACK 记录

    S-->>B: AckResp (0x0053)<br>ack_type = RECEIVED (0)<br>← 服务端确认 ACK 已处理

    S->>A: AckNotify (0x0054)<br>message_ids=[msgId1, msgId2, ...]<br>ack_type = RECEIVED (0)<br>← 发送方被告知"消息已送达对方"

    Note over A,B: 阶段四（可选）：消息已读确认

    B->>S: AckReq (0x0052)<br>message_ids=[msgId1, msgId2, ...]<br>ack_type = SEEN (1)<br>← 接收方确认"消息已读"

    Note over S: 更新消息状态为"已读"

    S-->>B: AckResp (0x0053)<br>ack_type = SEEN (1)

    S->>A: AckNotify (0x0054)<br>message_ids=[msgId1, msgId2, ...]<br>ack_type = SEEN (1)<br>← 发送方被通知"消息已被对方读取"

```

## AckType 说明

| 值 | 枚举 | 含义 | 触发时机 |
|----|------|------|----------|
| 0 | `RECEIVED` | 已收到 | B 的客户端收到消息后自动发送 |
| 1 | `SEEN` | 已读 | B 进入聊天界面查看消息后发送 |

## 关键设计要点

1. **C2CResp 不等于对方已收到** — 此响应仅意味着服务端已接收并存储了消息。此时 B 可能离线，消息会进入离线消息队列。
2. **双阶段 ACK** — `RECEIVED` 表示消息已推送到 B 的设备，`SEEN` 表示 B 已阅读。由业务层决定是否需要区分。
3. **AckNotify 是异步的** — ACK 通知是独立推送，与原始 C2CReq 不共享同一个事务或响应周期。
4. **seq 序列号** — 每个 C2C 会话（A↔B）各自维护单调递增的 seq，用于消息排序和断线重连时的增量同步。
5. **离线消息** — 如果 B 不在线，C2CNotify 跳过，消息存入离线队列。B 上线后通过 `PULL_REQ`/`PULL_RESP` 拉取。
