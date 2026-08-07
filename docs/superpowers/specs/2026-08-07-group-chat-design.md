# 群聊功能设计方案

## 概述

在 Pomelo IM 现有 C2C 单聊架构基础上，扩展完整群聊功能。采用**读扩散**模型，复用 seqsvr 基础设施生成群维度全局 seq。

### 当前状态

| 模块 | 已有 | 缺少 |
|------|------|------|
| Proto | `C2GReq/Resp/Notify`、Cmd 0x0020-0x0022 | 群管理 proto、群消息拉取 proto、群 ACK proto |
| DB | `im_message_group`（消息表）、`im_group_member`（成员表） | `im_group`（群元数据表） |
| Service | `C2GService` stub | 完整消息发送、群管理、群消息拉取、群 ACK |
| Gateway | `CMD_C2G_REQ → logic.c2g` 已接通 | 群管理和拉取路由 |
| Web SDK | `Cmd.C2G_*` 常量已定义 | 群消息收发、群管理方法 |
| Terminal | 无 | 群列表面板、群聊收发、群管理命令 |

### 消息模型

**读扩散：** 每条群消息只存一份（`im_message_group` 表），群成员共享读取。群维度全局 seq 由 seqsvr 分配，`groupId → toSectionId(groupId) → alloc 节点 → fetchNextSequence()`。

| 维度 | C2C（写扩散） | C2G（读扩散） |
|------|---------------|---------------|
| 写入 | 1 条消息写 1 份到收件人 inbox | 1 条消息写 1 份到群 inbox |
| seq | 收件人维度 | 群维度 |
| 离线拉取 | `pullPending(recipientId, sinceSeq)` | `PullGroupMsg(seq > cursor)` |
| 已读标记 | 逐条 `UPDATE status=2` | 游标 `last_read_seq` |
| 未读计数 | `COUNT WHERE status < 2` | `COUNT WHERE seq > last_read_seq` |

---

## 一、Proto 层

### 1.1 复用现有

`group.proto` — `C2GReq`/`C2GResp`/`C2GNotify`（不变）

```
CMD_C2G_REQ    = 0x0020
CMD_C2G_RESP   = 0x0021
CMD_C2G_NOTIFY = 0x0022
```

### 1.2 新增：群管理与消息拉取

新增文件 `group_mgmt.proto`（`package im.group`）：

```
// ============ 群管理操作 ============
CMD_GROUP_CREATE_REQ           = 0x0070
CMD_GROUP_CREATE_RESP          = 0x0071
CMD_GROUP_INVITE_REQ           = 0x0072
CMD_GROUP_INVITE_RESP          = 0x0073
CMD_GROUP_JOIN_REQ             = 0x0074    // 申请加群
CMD_GROUP_JOIN_RESP            = 0x0075
CMD_GROUP_HANDLE_JOIN_REQ      = 0x0076    // 群主审批加群
CMD_GROUP_HANDLE_JOIN_RESP     = 0x0077
CMD_GROUP_LEAVE_REQ            = 0x0078
CMD_GROUP_LEAVE_RESP           = 0x0079
CMD_GROUP_KICK_REQ             = 0x007A
CMD_GROUP_KICK_RESP            = 0x007B
CMD_GROUP_SET_ADMIN_REQ        = 0x007C
CMD_GROUP_SET_ADMIN_RESP       = 0x007D
CMD_GROUP_TRANSFER_OWNER_REQ   = 0x007E
CMD_GROUP_TRANSFER_OWNER_RESP  = 0x007F
CMD_GROUP_MUTE_REQ             = 0x0080
CMD_GROUP_MUTE_RESP            = 0x0081
CMD_GROUP_UPDATE_INFO_REQ      = 0x0082    // 修改群名/公告/头像
CMD_GROUP_UPDATE_INFO_RESP     = 0x0083
CMD_GROUP_DISMISS_REQ          = 0x0084
CMD_GROUP_DISMISS_RESP         = 0x0085
CMD_GROUP_GET_INFO_REQ         = 0x0086
CMD_GROUP_GET_INFO_RESP        = 0x0087
CMD_GROUP_GET_MEMBERS_REQ      = 0x0088
CMD_GROUP_GET_MEMBERS_RESP     = 0x0089
CMD_GROUP_GET_MY_GROUPS_REQ    = 0x0090    // 获取我的群列表
CMD_GROUP_GET_MY_GROUPS_RESP   = 0x0091

// ============ 推送通知（S→C）============
CMD_GROUP_JOIN_REQUEST_NOTIFY  = 0x0092    // 申请加群通知（给群主/管理员）
CMD_GROUP_MEMBER_CHANGE_NOTIFY = 0x0093    // 成员变更通知（给全群）

// ============ 群消息拉取 ============
CMD_GROUP_PULL_MSG_REQ         = 0x0094
CMD_GROUP_PULL_MSG_RESP        = 0x0095

// ============ 群 ACK ============
CMD_GROUP_ACK_REQ              = 0x0096
CMD_GROUP_ACK_RESP             = 0x0097
```

#### 关键消息体

```protobuf
message CreateGroupReq {
    string name    = 1;
    string avatar  = 2;
}

message CreateGroupResp {
    int32 code      = 1;
    string message  = 2;
    GroupInfo group = 3;
}

message GroupInfo {
    string group_id    = 1;
    string name        = 2;
    string avatar      = 3;
    string description = 4;
    string owner_id    = 5;
    int32 member_count = 6;
    int32 max_members  = 7;
    int64 created_at   = 8;
}

message GroupMemberChangeNotify {
    string group_id     = 1;
    ChangeType type     = 2;   // INVITED, JOINED, LEFT, KICKED, ADMIN_SET, OWNER_TRANSFERRED
    string user_id      = 3;   // 变更对象
    string operator_id  = 4;   // 操作者
    string user_name    = 5;   // 变更对象显示名
    string nickname     = 6;

    enum ChangeType {
        INVITED = 0;
        JOINED = 1;
        LEFT = 2;
        KICKED = 3;
        ADMIN_SET = 4;
        OWNER_TRANSFERRED = 5;
    }
}

message PullGroupMsgReq {
    string group_id   = 1;
    int64 cursor      = 2;   // seq 游标
    int32 limit       = 3;   // 默认 50
    bool is_backward  = 4;   // true=拉历史(seq<cursor倒序)，false=拉增量(seq>cursor正序)
}

message PullGroupMsgResp {
    int32 code              = 1;
    string message          = 2;
    repeated GroupMsgRecord messages = 3;
    bool has_more           = 4;
}

message GroupMsgRecord {
    string id       = 1;
    string sender_id = 2;
    string group_id = 3;
    int32 msg_type  = 4;
    string content  = 5;
    int64 seq       = 6;
    int64 created_at = 7;
    string sender_name = 8;
    string sender_nickname = 9;
}

message GroupAckReq {
    string group_id      = 1;
    int64 last_read_seq  = 2;
}
```

### 1.3 message.proto 说明

`message.proto` 的 `MsgBody.oneof body` **无需更新**。`ProtobufCodec` 通过 cmd 值索引静态 `Parser[256]` 数组来路由解析器，不依赖 `MsgBody` oneof。群管理 proto 注册方式与 `relation.proto`（好友操作）一致——在 `ProtobufCodec` 静态块和 `CodecRegistry` 中按 cmd 注册即可。

---

## 二、DB Schema

### 2.1 新增 `im_group` 表

```sql
CREATE TABLE IF NOT EXISTS im_group (
    id           VARCHAR(64)  PRIMARY KEY,      -- NanoID 系统生成
    name         VARCHAR(128) NOT NULL,          -- 群名
    avatar       VARCHAR(512),                  -- 群头像 URL
    description  TEXT,                           -- 群公告/简介
    owner_id     VARCHAR(64) NOT NULL,           -- 群主 userId (NanoID)
    max_members  INT         NOT NULL DEFAULT 200,
    created_at   BIGINT      NOT NULL,
    updated_at   BIGINT      NOT NULL
);
```

### 2.2 修改 `im_group_member` 表

```sql
ALTER TABLE im_group_member
  ADD COLUMN IF NOT EXISTS last_read_seq BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS muted_until   BIGINT NOT NULL DEFAULT 0;
```

完整结构：
- `group_id` VARCHAR(64) NOT NULL — 群 ID
- `user_id` VARCHAR(64) NOT NULL — 用户 NanoID
- `role` SMALLINT NOT NULL DEFAULT 0 — 0=成员, 1=管理员, 2=群主
- `last_read_seq` BIGINT NOT NULL DEFAULT 0 — 已读游标
- `muted_until` BIGINT NOT NULL DEFAULT 0 — 禁言截止时间戳（0=未禁言）
- `joined_at` BIGINT NOT NULL
- PRIMARY KEY (group_id, user_id)

### 2.3 `im_message_group` 表（不变）

已有索引覆盖所有查询模式：
- `idx_group_conversation (group_id, created_at DESC)` — 历史拉取
- `idx_group_seq (seq)` — 增量拉取 `WHERE group_id = $1 AND seq > $2`
- `idx_group_created_at_brin` — 时间范围扫描

---

## 三、服务层

### 3.1 核心写入路径：C2GService

```
C2GReq → decode
  → 校验：sender 是群成员 + 未被禁言（muted_until < now）
  → seqClient.fetchNextSequence(groupId) → 群维度全局 seq
  → INSERT INTO im_message_group (id, sender_id, group_id, msg_type, content, seq, created_at)
  → 读扩散推送：
      SELECT user_id FROM im_group_member WHERE group_id = $1
      过滤掉 sender 自己
      对每个成员 PushRouter.push(C2GNotify { senderId, groupId, content, seq })
  → C2GResp { code=0, messageId, groupId, seq, serverTime }
```

### 3.2 群管理：GroupManagementService

| 操作 | 前置条件 | 效果 |
|------|----------|------|
| CreateGroup | name 非空 | INSERT im_group + INSERT creator as owner(role=2) |
| InviteToGroup | 操作者管理员/群主 | INSERT member, 推送 MemberChangeNotify(INVITED) |
| JoinGroup | 任何人 | 推送 JoinRequestNotify 给群主/管理员 |
| HandleJoin | 群主/管理员 | 同意→INSERT member+推送；拒绝→无 |
| LeaveGroup | 非群主 | DELETE member, 推送 MemberChangeNotify(LEFT) |
| KickMember | 操作者管理员/群主，目标非群主 | DELETE member, 推送 MemberChangeNotify(KICKED) |
| SetAdmin | 群主 | UPDATE member SET role=1 |
| TransferOwner | 群主 | UPDATE group.owner_id, 原群主→role=0, 新群主→role=2 |
| MuteMember | 管理员/群主 | UPDATE member SET muted_until=时间戳 |
| UpdateGroupInfo | 管理员/群主 | UPDATE group SET name/avatar/description |
| DismissGroup | 群主 | DELETE group + DELETE all members + 推送 |
| GetGroupInfo | 群成员 | SELECT group + members count |
| GetGroupMembers | 群成员 | SELECT members WHERE group_id=$1 |
| GetMyGroups | 任何人 | SELECT groups WHERE user is member |

### 3.3 群消息拉取：GroupPullService

```
PullGroupMsgReq → decode
  → is_backward=true:
      SELECT * FROM im_message_group
      WHERE group_id=$1 AND seq < $2 ORDER BY seq DESC LIMIT $3
  → is_backward=false:
      SELECT * FROM im_message_group
      WHERE group_id=$1 AND seq > $2 ORDER BY seq LIMIT $3
  → 批量查 sender 的 userId + nickname
  → PullGroupMsgResp { messages[], hasMore }
```

### 3.4 群 ACK：GroupAckService

```
GroupAckReq → decode
  → UPDATE im_group_member
    SET last_read_seq = GREATEST(last_read_seq, $1)
    WHERE group_id = $2 AND user_id = $3
  → GroupAckResp { code=0 }
```

### 3.5 EventBus 注册（LogicVerticle）

```java
bus.consumer("logic.c2g",    msg -> dispatch(msg, c2gService::process));
bus.consumer("logic.group",  msg -> dispatch(msg, groupService::process));
bus.consumer("logic.gpull",  msg -> dispatch(msg, groupPullService::process));
bus.consumer("logic.gack",   msg -> dispatch(msg, groupAckService::process));
```

### 3.6 Gateway 路由（MessageDispatcher）

```java
// cmdToAddress 新增：
if (cmd == CMD_C2G_REQ_VALUE)            return "logic.c2g";
if (cmd == CMD_GROUP_PULL_MSG_REQ_VALUE)  return "logic.gpull";
if (cmd == CMD_GROUP_ACK_REQ_VALUE)       return "logic.gack";
// 群管理 Cmd 0x0070-0x0097 统一路由
if (cmd >= 0x0070 && cmd <= 0x0097)       return "logic.group";
```

---

## 四、Go 终端适配

### 4.1 SDK 新增（pomelo-terminal-go）

**`pkg/models/models.go`** — 新增：
- Cmd 常量（GROUP_CREATE_REQ 等 0x0070-0x008F）
- `GroupInfo`、`GroupMsgRecord`、`GroupMemberInfo` struct
- `GroupMessage` 事件类型

**`pkg/sdk/client.go`** — 新增方法：
- `SendGroupMessage(groupId, content string, msgType int) (string, error)`
- `PullGroupMessages(groupId string, cursor int64, limit int, backward bool) ([]GroupMsgRecord, bool, error)`
- `SendGroupAck(groupId string, lastReadSeq int64) error`
- `CreateGroup(name string) (*GroupInfo, error)`
- `InviteToGroup/LeaveGroup/KickMember/GetGroupInfo/GetGroupMembers/GetMyGroups`
- `HandleGroupNotify` 分发：`GroupMemberChangeNotify` → TUI 事件

### 4.2 TUI 层

**左侧面板 Tab 循环：** `好友列表 → 群列表 → 会话列表 → 好友列表 ...`

**`renderGroupPanel(h int)`**：群名 + 未读红点，`▶` 选中进入群聊

**新命令：**
```
/create <name>           创建群
/invite <gid> <uid>      邀请入群
/leave <gid>             退群
/groups                  群列表
/gmembers <gid>          群成员
/ginfo <gid>             群信息
```

**群聊右面板：** 和 C2C 聊天共用消息列表渲染，区别在于消息来源和 ACK 模式（游标式 vs 逐条式）。

---

## 五、Web 端适配

### 5.1 SDK 层

**`types.ts`** 新增：
- 群管理 Cmd 常量（0x0070-0x008F）
- `GroupInfo`、`GroupMsgRecord`、`GroupMember`、`GroupMemberChangeNotify` 类型
- `GroupOpResp`、`PullGroupMsgResp` 响应类型
- `IMClientEvents` 新增 `groupMessage`、`groupMemberChange`、`groupJoinRequest` 事件

**`client.ts`** 新增方法：
- `sendGroupMessage(groupId, msgType, content): string`
- `pullGroupMessages(groupId, cursor, limit, backward): Promise<PullGroupMsgResp>`
- `sendGroupAck(groupId, lastReadSeq): void`
- 群管理操作（复用 `_sendFriendOp` 的 messageId-Promise 模式）：`createGroup`、`inviteToGroup`、`leaveGroup`、`kickMember` 等
- `_dispatchMessage` 新增 C2G_NOTIFY、GROUP_MEMBER_CHANGE_NOTIFY 等分支

### 5.2 Store 层

**`Conversation` 扩展 `type` 字段：**
```typescript
export interface Conversation {
  peerId: string;
  type: 'c2c' | 'group';   // 新增
  nickname: string;
  avatar: string;
  lastMessage: string;
  lastMessageTime: number;
  unreadCount: number;
  draft?: string;
}
```

**新建 `useGroupStore.ts`：**
```typescript
interface GroupState {
  groups: Record<string, GroupInfo>;
  groupMembers: Record<string, GroupMember[]>;
  groupLastReadSeq: Record<string, number>;
  // actions: setGroups, addGroup, setMembers, updateLastReadSeq...
}
```

**`useChatStore` 保持不变：** 消息按 `peerId` 存储，C2C 和 C2G 共用 `messages[peerId]`。发送时根据 `conversation.type` 区分调用 `sendMessage`（C2C）或 `sendGroupMessage`（C2G）。

### 5.3 UI 层

**`Chat/index.tsx` 主要变更：**

1. 左侧 Tab：`聊天` → `群聊` → `好友`（三栏，当前两栏）
2. 群聊 Tab 面板（`GroupPanel` 组件）：群列表，展示群名+头像+未读红点
3. 群聊右侧面板：标题栏显示群名+成员数→点击展开成员侧边栏
4. 输入框：支持 `/invite @uid` 快捷命令
5. ACK 逻辑分叉：群聊用 `sendGroupAck`（游标），C2C 用 `markSeen`（逐条）
6. 已读回执 useEffect：群聊不上报已读（游标在打开群聊时一次性上报）

**新组件：**
- `GroupPanel` — 群列表（复用 ConversationItem 样式）
- `GroupInfoPanel` — 群信息侧边栏（成员列表、群设置）

---

## 六、分期实施

### 第一期：核心 — 群聊消息收发

| 模块 | 内容 |
|------|------|
| DB | `im_group` 表 + `im_group_member` 加 `last_read_seq`/`muted_until` |
| Proto | `group_mgmt.proto`：CreateGroup、GetGroupInfo、GetGroupMembers、GetMyGroups、PullGroupMsg、GroupAck、GroupMemberChangeNotify |
| Service | `C2GService.doSend()` — 发消息 + seqsvr seq + 读扩散推送 |
| Service | `GroupPullService` — 增量/历史拉取 |
| Service | `GroupAckService` — 更新 last_read_seq |
| Service | `GroupManagementService`（部分）— CreateGroup、GetGroupInfo、GetGroupMembers、GetMyGroups |
| Gateway | `cmdToAddress` 新增 `logic.gpull`/`logic.gack`/`logic.group` |
| Go 终端 | 群列表面板 + 群聊收发 + `/groups`/`/ginfo`/`/gmembers`/`/create` |
| Web | 群聊 Tab + 群消息收发 + 群 ACK |

### 第二期：管理 — 群 CRUD + 成员管理

| 模块 | 内容 |
|------|------|
| Proto | InviteToGroup、JoinGroup、HandleJoin、LeaveGroup、KickMember、SetAdmin、TransferOwner、DismissGroup、UpdateGroupInfo |
| Proto | JoinRequestNotify |
| Service | `GroupManagementService` 全部管理操作 + 权限校验 |
| Go 终端 | `/invite`、`/leave` 命令 + 群通知展示 |
| Web | 群管理操作 + 群通知卡片 |

### 第三期：高级 — 禁言 + @提及 + 免打扰

| 模块 | 内容 |
|------|------|
| Proto | MuteMember + C2GReq 加 `mentioned_users` 字段 |
| Service | C2GService 禁言拦截 + @ 推送标记 |
| DB | `im_group_member` 加 `mute_notify` 列（消息免打扰） |
| Go 终端 | @高亮、禁言提示 |
| Web | @高亮、禁言提示、免打扰开关 |

---

## 七、设计决策记录

1. **读扩散** — 群消息只存一份，seqsvr 为 `groupId` 分配群维度全局 seq。比写扩散省 N 倍存储和写入，seqsvr 基础设施复用。
2. **群管理独立 Cmd 区间** — 0x0070-0x0097，不与聊天消息(0x0020)混。Gateway 统一路由到 `logic.group`。
3. **群消息拉取独立协议** — `PullGroupMsgReq/Resp` 独立于 C2C 的 `PullReq/Resp`，查询模式和表都不同。
4. **已读用游标而非逐条标记** — `last_read_seq` 一个值搞定，简单高效，和读扩散天然匹配。
5. **单 Service 聚合群管理** — `GroupManagementService` 集中处理所有管理操作，权限校验统一，避免分散在多个 Service 中。
6. **Web 端 Conversation 统一模型** — C2C 和群聊共用 `Conversation` 数据结构，通过 `type` 字段区分。`useChatStore` 不变，消息存储按 `peerId` 无感。
