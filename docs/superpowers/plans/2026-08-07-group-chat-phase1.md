# 群聊第一期：核心消息收发 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现群聊核心消息收发链路：创建群 → 发群消息（读扩散）→ 推送给群成员 → 增量/历史拉取 → 游标式已读 ACK。覆盖 Java 后端、Web 前端、Go 终端。

**Architecture:** 读扩散模型，群维度全局 seq 由 seqsvr 分配，消息只存一份到 `im_message_group`，推送通过 `PushRouter` 遍历成员投递。已读通过 `im_group_member.last_read_seq` 游标维护。

**Tech Stack:** Java 17 + Vert.x 5.0.8 + PostgreSQL 17 + Protobuf 3 + seqsvr, TypeScript + React + Zustand (Web), Go + Bubble Tea (Terminal)

## Global Constraints

- 编码风格：禁止完全限定类名（必须 import）、禁止行尾注释
- Proto 变更后需运行 `./mvnw clean compile -pl pomelo-common -am`
- Service 必须继承 `ServiceBase`，通过 `CodecRegistry` 解码
- 所有 DB 操作通过 Repository 接口 + PG 实现
- 响应构建使用 `ServiceBase.buildResponse(request, cmd, body)`
- 构建前处理 proxy：`mv .mvn/jvm.config .mvn/jvm.config.bak`，构建后 restore

---

### Task 1: Proto + Cmd + ProtobufCodec

**Files:**
- Modify: `pomelo-common/src/main/proto/common/common.proto`
- Create: `pomelo-common/src/main/proto/group/group_mgmt.proto`
- Modify: `pomelo-common/src/main/java/com/github/moxib/pomelo/codec/ProtobufCodec.java`

**Produces:** 所有群管理 Cmd 常量 + GroupMgmtProto Java 类 + ProtobufCodec parser 注册

- [ ] **1.1** 在 `common.proto` 的 Cmd 枚举中，`CMD_FRIEND_DELETE_NOTIFY = 0x006A;` 之后、`CMD_ERROR = 0xFFFF;` 之前，插入新 Cmd：

```protobuf
    CMD_GROUP_CREATE_REQ    = 0x0070;  CMD_GROUP_CREATE_RESP   = 0x0071;
    CMD_GROUP_GET_INFO_REQ  = 0x0086;  CMD_GROUP_GET_INFO_RESP = 0x0087;
    CMD_GROUP_GET_MEMBERS_REQ  = 0x0088;  CMD_GROUP_GET_MEMBERS_RESP = 0x0089;
    CMD_GROUP_GET_MY_GROUPS_REQ  = 0x0090;  CMD_GROUP_GET_MY_GROUPS_RESP = 0x0091;
    CMD_GROUP_MEMBER_CHANGE_NOTIFY = 0x0093;
    CMD_GROUP_PULL_MSG_REQ  = 0x0094;  CMD_GROUP_PULL_MSG_RESP = 0x0095;
    CMD_GROUP_ACK_REQ       = 0x0096;  CMD_GROUP_ACK_RESP      = 0x0097;
```

- [ ] **1.2** 创建 `group_mgmt.proto` — 完整内容参见设计文档 Section 1.2。关键消息体：`CreateGroupReq/Resp`、`GroupInfo`、`GetGroupInfoReq/Resp`、`GetGroupMembersReq/Resp`、`GetMyGroupsReq/Resp`、`GroupMemberChangeNotify`、`PullGroupMsgReq/Resp`（含 `GroupMsgRecord`）、`GroupAckReq/Resp`。

- [ ] **1.3** 运行 `./mvnw clean compile -pl pomelo-common -am`，确认 `GroupMgmtProto.java` 已生成。

- [ ] **1.4** 在 `ProtobufCodec.java` 的 `static {` 块末尾（好友关系注册之后）添加 `registerProto` 调用，并且 import `GroupMgmtProto`：

```java
import com.github.moxib.pomelo.proto.group.GroupMgmtProto;

// 在 static 块内添加：
registerProto(CMD_GROUP_CREATE_REQ_VALUE, GroupMgmtProto.CreateGroupReq.parser(), GroupMgmtProto.CreateGroupReq.class);
registerProto(CMD_GROUP_CREATE_RESP_VALUE, GroupMgmtProto.CreateGroupResp.parser(), GroupMgmtProto.CreateGroupResp.class);
registerProto(CMD_GROUP_GET_INFO_REQ_VALUE, GroupMgmtProto.GetGroupInfoReq.parser(), GroupMgmtProto.GetGroupInfoReq.class);
registerProto(CMD_GROUP_GET_INFO_RESP_VALUE, GroupMgmtProto.GetGroupInfoResp.parser(), GroupMgmtProto.GetGroupInfoResp.class);
registerProto(CMD_GROUP_GET_MEMBERS_REQ_VALUE, GroupMgmtProto.GetGroupMembersReq.parser(), GroupMgmtProto.GetGroupMembersReq.class);
registerProto(CMD_GROUP_GET_MEMBERS_RESP_VALUE, GroupMgmtProto.GetGroupMembersResp.parser(), GroupMgmtProto.GetGroupMembersResp.class);
registerProto(CMD_GROUP_GET_MY_GROUPS_REQ_VALUE, GroupMgmtProto.GetMyGroupsReq.parser(), GroupMgmtProto.GetMyGroupsReq.class);
registerProto(CMD_GROUP_GET_MY_GROUPS_RESP_VALUE, GroupMgmtProto.GetMyGroupsResp.parser(), GroupMgmtProto.GetMyGroupsResp.class);
registerProto(CMD_GROUP_MEMBER_CHANGE_NOTIFY_VALUE, GroupMgmtProto.GroupMemberChangeNotify.parser(), GroupMgmtProto.GroupMemberChangeNotify.class);
registerProto(CMD_GROUP_PULL_MSG_REQ_VALUE, GroupMgmtProto.PullGroupMsgReq.parser(), GroupMgmtProto.PullGroupMsgReq.class);
registerProto(CMD_GROUP_PULL_MSG_RESP_VALUE, GroupMgmtProto.PullGroupMsgResp.parser(), GroupMgmtProto.PullGroupMsgResp.class);
registerProto(CMD_GROUP_ACK_REQ_VALUE, GroupMgmtProto.GroupAckReq.parser(), GroupMgmtProto.GroupAckReq.class);
registerProto(CMD_GROUP_ACK_RESP_VALUE, GroupMgmtProto.GroupAckResp.parser(), GroupMgmtProto.GroupAckResp.class);
```

- [ ] **1.5** `./mvnw test -pl pomelo-common` → PASS
- [ ] **1.6** Commit: `git add` proto files + ProtobufCodec + generated code, commit "feat: 新增群管理 proto + Cmd + ProtobufCodec 注册"

---

### Task 2: DB Schema

**Files:**
- Modify: `db/schema.sql`

- [ ] **2.1** 在 `schema.sql` 中添加 `im_group` 表（位置：im_user 之后）：

```sql
CREATE TABLE IF NOT EXISTS im_group (
    id           VARCHAR(64)  PRIMARY KEY,
    name         VARCHAR(128) NOT NULL,
    avatar       VARCHAR(512),
    description  TEXT,
    owner_id     VARCHAR(64) NOT NULL,
    max_members  INT         NOT NULL DEFAULT 200,
    created_at   BIGINT      NOT NULL,
    updated_at   BIGINT      NOT NULL
);
```

- [ ] **2.2** 修改 `im_group_member` 表，在 `role` 和 `joined_at` 之间添加新列：

```sql
    last_read_seq  BIGINT  NOT NULL DEFAULT 0,
    muted_until    BIGINT  NOT NULL DEFAULT 0,
```

- [ ] **2.3** 应用变更：

```bash
docker compose exec postgres psql -U pomelo -d pomelo_db <<'SQL'
CREATE TABLE IF NOT EXISTS im_group (
    id VARCHAR(64) PRIMARY KEY, name VARCHAR(128) NOT NULL, avatar VARCHAR(512),
    description TEXT, owner_id VARCHAR(64) NOT NULL, max_members INT NOT NULL DEFAULT 200,
    created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL);
ALTER TABLE im_group_member ADD COLUMN IF NOT EXISTS last_read_seq BIGINT NOT NULL DEFAULT 0;
ALTER TABLE im_group_member ADD COLUMN IF NOT EXISTS muted_until BIGINT NOT NULL DEFAULT 0;
SQL
```

- [ ] **2.4** 验证：`\d im_group` 和 `\d im_group_member` 确认列正确
- [ ] **2.5** Commit: `git add db/schema.sql`, commit "feat: im_group 表 + im_group_member 扩展 last_read_seq/muted_until"

---

### Task 3: Model 类 + GroupRepository

**Files:**
- Create: `pomelo-logic-server/src/main/java/.../logic/model/GroupInfo.java`
- Create: `pomelo-logic-server/src/main/java/.../logic/model/GroupMemberRecord.java`
- Create: `pomelo-logic-server/src/main/java/.../logic/model/GroupMsgContext.java`
- Create: `pomelo-logic-server/src/main/java/.../logic/infrastructure/GroupRepository.java`
- Create: `pomelo-logic-server/src/main/java/.../logic/infrastructure/PgGroupRepository.java`
- Create: `pomelo-logic-server/src/main/java/.../logic/infrastructure/GroupMsgWithSender.java`

**关键接口（供后续 Task 引用）：**

```java
// GroupRepository 接口
public interface GroupRepository {
  Future<Void> createGroup(GroupInfo group);
  Future<GroupInfo> findById(String groupId);
  Future<List<GroupInfo>> findGroupsByUserId(String userId);
  Future<List<GroupMemberRecord>> findMembers(String groupId);
  Future<Void> addMember(String groupId, String userId, int role, long now);
  Future<Void> removeMember(String groupId, String userId);
  Future<Boolean> isMember(String groupId, String userId);
  Future<Void> updateLastReadSeq(String groupId, String userId, long seq);
  Future<Boolean> saveMessage(long id, String groupId, long senderNumericId,
      int msgType, String content, long seq, long createdAt);
  Future<List<GroupMsgWithSender>> pullMessages(String groupId, long cursor, int limit, boolean backward);
}

// GroupMsgWithSender 字段: id(long), senderNumericId(long), groupId(String),
//   msgType(int), content(String), seq(long), createdAt(long) — POJO + getters
```

**PG 实现 SQL 映射：**
- `findById`: JOIN `(SELECT COUNT(*) FROM im_group_member WHERE group_id=id) AS member_count`
- `findGroupsByUserId`: JOIN `im_group_member` ON g.id = gm.group_id WHERE gm.user_id = $1
- `findMembers`: JOIN `im_user` ON gm.user_id = u.user_id
- `saveMessage`: INSERT INTO `im_message_group` ON CONFLICT DO NOTHING
- `pullMessages`: backward→`WHERE seq < $2 ORDER BY seq DESC`, forward→`WHERE seq > $2 ORDER BY seq`

- [ ] **3.1** 创建 `GroupInfo.java` — Builder pattern，8 字段（groupId, name, avatar, description, ownerId, memberCount, maxMembers, createdAt, updatedAt）
- [ ] **3.2** 创建 `GroupMemberRecord.java` — Builder pattern，7 字段（groupId, userId, userName, nickname, avatar, role, joinedAt）
- [ ] **3.3** 创建 `GroupMsgContext.java` — Builder pattern，8 字段（messageId, groupId, senderUserId, senderUserName, senderNickname, msgType, content, timestamp, codecId）
- [ ] **3.4** 创建 `GroupMsgWithSender.java` — 6 字段 POJO + constructor + getters
- [ ] **3.5** 创建 `GroupRepository.java` 接口 — 11 个方法签名
- [ ] **3.6** 创建 `PgGroupRepository.java` — 实现 11 个方法，SQL 常量为 text blocks。参考 `PgMessageRepository` 风格：`pool.preparedQuery(SQL).execute(Tuple.of(...)).map(...)`
- [ ] **3.7** `./mvnw compile -pl pomelo-logic-server -am` → BUILD SUCCESS
- [ ] **3.8** Commit: `git add` 6 个新文件, commit "feat: GroupInfo/GroupMemberRecord/GroupMsgContext model + GroupRepository + PgGroupRepository"

---

### Task 4: C2GService — 群消息发送（读扩散核心）

**Files:**
- Modify: `pomelo-logic-server/src/main/java/.../logic/service/C2GService.java`

**流程：** 解码 C2GReq → 校验 isMember + 禁言检查 → seqClient.fetchNextSequence(groupId) → saveMessage → 遍历成员 PushRouter.push(C2GNotify) 跳过发送者 → C2GResp

- [ ] **4.1** 重写 `C2GService.java`（当前是 stub），构造器改为接收 `Vertx, PushRouter, GroupRepository, SeqClientService, SnowflakeIdGenerator, SessionRouteTable`
- [ ] **4.2** 实现 `process(ImMessage)`：从 varHeaders 取 userId/userName/nickname，从 body JSON 取 groupId/message/msgType
- [ ] **4.3** 实现 `doSend(GroupMsgContext)`：`snowflake.nextId()` + `seqClient.fetchNextSequence(ctx.getGroupId())` → `groupRepo.saveMessage()` → 成功则调用 `pushToGroupMembers()`
- [ ] **4.4** 实现 `pushToGroupMembers()`：`groupRepo.findMembers()` → 过滤 sender → 对每个成员 `routeTable.resolveCodec()` 构建 PB 或 JSON 的 C2GNotify → `pushRouter.push(PushEnvelope)`
- [ ] **4.5** 参考已有 `C2CService.publishC2CNotify()` 的 PB/JSON 双 codec 分支模式，C2GNotify 的 `message` 字段用 `CommonProto.MessageContent`（PB）或 `JsonObject`（JSON）
- [ ] **4.6** `./mvnw compile -pl pomelo-logic-server -am` → BUILD SUCCESS
- [ ] **4.7** Commit: commit "feat: 实现 C2GService 群消息发送（读扩散推送）"

---

### Task 5: GroupPullService + GroupAckService

**Files:**
- Create: `pomelo-logic-server/src/main/java/.../logic/service/GroupPullService.java`
- Create: `pomelo-logic-server/src/main/java/.../logic/service/GroupAckService.java`

- [ ] **5.1** 创建 `GroupPullService.java`：
  - 实现 `process(ImMessage)`：从 body JSON 取 `groupId, cursor(seq), limit, isBackward`
  - `groupRepo.pullMessages()` → 收集 senderNumericId → `messageRepo.findUserIdsByIds()` 查显示名 → 构建 `PullGroupMsgResp`（PB 用 `GroupMgmtProto.GroupMsgRecord`，JSON 用 `JsonArray`）
  - 参考 `PullService.sendPullResp()` 的双 codec 构建模式
  - 默认 limit=50

- [ ] **5.2** 创建 `GroupAckService.java`：
  - 实现 `process(ImMessage)`：从 body JSON 取 `groupId, lastReadSeq`，从 headers 取 userId
  - 校验 `groupRepo.isMember()` → `groupRepo.updateLastReadSeq()` (GREATEST)
  - 返回 `GroupAckResp { code: 0 }`

- [ ] **5.3** `./mvnw compile -pl pomelo-logic-server -am` → BUILD SUCCESS
- [ ] **5.4** Commit: commit "feat: GroupPullService + GroupAckService"

---

### Task 6: GroupManagementService（第一期）

**Files:**
- Create: `pomelo-logic-server/src/main/java/.../logic/service/GroupManagementService.java`

- [ ] **6.1** 实现 4 个方法：
  - `handleCreateGroup`: 生成 NanoID groupId → `groupRepo.createGroup()` → `groupRepo.addMember(creator, role=2)` → 返回 `CreateGroupResp` 含 `GroupInfo`
  - `handleGetGroupInfo`: `groupRepo.findById()` → `GetGroupInfoResp`
  - `handleGetMembers`: `groupRepo.findMembers()` → `GetGroupMembersResp`
  - `handleGetMyGroups`: `groupRepo.findGroupsByUserId()` → `GetMyGroupsResp`
- [ ] **6.2** 用 `if (cmd == CMD_XXX_REQ_VALUE)` 分支分发，未来扩展管理操作只需添加 if 分支
- [ ] **6.3** `./mvnw compile` → BUILD SUCCESS

---

### Task 7: LogicVerticle + Gateway 路由

**Files:**
- Modify: `pomelo-logic-server/src/main/java/.../logic/LogicVerticle.java`
- Modify: `pomelo-gateway/src/main/java/.../gateway/handler/MessageDispatcher.java`

- [ ] **7.1** `LogicVerticle.java`：添加字段 `groupService, groupPullService, groupAckService`，初始化时传入依赖，注册 4 个 consumer：
```java
bus.consumer("logic.c2g",   msg -> dispatch(msg, c2gService::process));
bus.consumer("logic.group", msg -> dispatch(msg, groupService::process));
bus.consumer("logic.gpull", msg -> dispatch(msg, groupPullService::process));
bus.consumer("logic.gack",  msg -> dispatch(msg, groupAckService::process));
```

- [ ] **7.2** `MessageDispatcher.java` 在 `cmdToAddress()` 添加路由（已有 `CMD_C2G_REQ_VALUE → "logic.c2g"` 保留）：
```java
if (cmd == CMD_GROUP_PULL_MSG_REQ_VALUE)  return "logic.gpull";
if (cmd == CMD_GROUP_ACK_REQ_VALUE)       return "logic.gack";
if (cmd >= 0x0070 && cmd <= 0x0097)       return "logic.group";
```

- [ ] **7.3** `./mvnw test` → 所有已有测试 PASS
- [ ] **7.4** 构建 + 部署：
```bash
mv .mvn/jvm.config .mvn/jvm.config.bak
./mvnw clean compile jib:dockerBuild -pl pomelo-gateway,pomelo-logic-server,pomelo-seqsvr
mv .mvn/jvm.config.bak .mvn/jvm.config
docker compose up -d
docker compose logs pomelo-logic | grep "LogicVerticle"
```
预期：`LogicVerticle 已启动，所有 EventBus consumer 注册完成`

- [ ] **7.5** Commit: commit "feat: 注册群聊 Service Consumer + Gateway 路由"

---

### Task 8: Web SDK — types + client

**Files:**
- Modify: `pomelo-web/src/sdk/types.ts`
- Modify: `pomelo-web/src/sdk/client.ts`

**Produces:** `IMClient` 新增 `sendGroupMessage`, `pullGroupMessages`, `sendGroupAck`, `createGroup`, `getMyGroups`, `getGroupInfo`, `getGroupMembers`；`_dispatchMessage` 处理 C2G_NOTIFY/RESP 和群管理响应。

- [ ] **8.1** `types.ts` 新增：
  - Cmd 常量（`GROUP_CREATE_REQ = 0x0070` 等，对齐 common.proto）
  - `GroupInfo`, `GroupMsgRecord`, `GroupMember`, `GroupOpResp`, `PullGroupMsgResp`, `GroupMemberChangeNotify` 类型
  - `IMClientEvents` 新增：`groupMessage: (msg: GroupMessage) => void`, `groupInfoLoaded: (info: GroupInfo) => void`, `groupsLoaded: (list: GroupInfo[]) => void`, `groupMembersLoaded: (groupId: string, members: GroupMember[]) => void`
  - `ConversationType = 'c2c' | 'group'`

- [ ] **8.2** `client.ts` 新增方法：
  - `sendGroupMessage(groupId, msgType, content): string` — 构造 C2G_REQ，进入 pendingQueue
  - `pullGroupMessages(groupId, cursor, limit, backward): Promise<PullGroupMsgResp>` — PULL_GROUP_MSG_REQ，messageId-Promise 模式（复用 `pendingHistoryPulls` 或新 Map）
  - `sendGroupAck(groupId, lastReadSeq)` — GROUP_ACK_REQ，fire-and-forget
  - `createGroup(name): Promise<GroupInfo>` — GROUP_CREATE_REQ，`_sendPendingOp` 模式
  - `getMyGroups(): Promise<GroupInfo[]>` — GROUP_GET_MY_GROUPS_REQ
  - `getGroupInfo(groupId): Promise<GroupInfo>` — GROUP_GET_INFO_REQ
  - `getGroupMembers(groupId): Promise<GroupMember[]>` — GROUP_GET_MEMBERS_REQ

- [ ] **8.3** `client.ts` — `_dispatchMessage` 新增 case：
  - `Cmd.C2G_RESP`：关联 pendingQueue → status=’sent’
  - `Cmd.C2G_NOTIFY`：归一化为 GroupMessage → `emit('groupMessage', ...)`
  - `Cmd.GROUP_CREATE_RESP` 等管理响应 → pendingOps resolve
  - `Cmd.GROUP_PULL_MSG_RESP` → pendingPulls resolve
  - `Cmd.GROUP_MEMBER_CHANGE_NOTIFY` → `emit('groupMemberChange', ...)`

- [ ] **8.4** 编译验证：`cd pomelo-web && npx tsc --noEmit` → 无类型错误
- [ ] **8.5** Commit: commit "feat: Web SDK 群聊支持 — types + client 方法 + dispatch"

---

### Task 9: Web Store — useGroupStore + Conversation type 扩展

**Files:**
- Create: `pomelo-web/src/stores/useGroupStore.ts`
- Modify: `pomelo-web/src/stores/useConversationStore.ts`

- [ ] **9.1** 修改 `useConversationStore.ts` — `Conversation` 接口添加 `type: 'c2c' | 'group'`（默认 `'c2c'`），修改 `createConversation` 签名增加可选 `type` 参数
- [ ] **9.2** 创建 `useGroupStore.ts`：
  - State: `groups: Record<string, GroupInfo>`, `groupMembers: Record<string, GroupMember[]>`, `groupLastReadSeq: Record<string, number>`
  - Actions: `setGroups`, `addGroup`, `removeGroup`, `setMembers`, `updateMember`, `removeMember`, `updateLastReadSeq`
  - init: `loadMyGroups()` 调 SDK `getMyGroups()` 填充 groups

- [ ] **9.3** 编译验证：`npx tsc --noEmit`
- [ ] **9.4** Commit: commit "feat: useGroupStore + Conversation type 扩展"

---

### Task 10: Web UI — ChatPage 群聊支持

**Files:**
- Create: `pomelo-web/src/components/GroupPanel/index.tsx`
- Modify: `pomelo-web/src/pages/Chat/index.tsx`
- Modify: `pomelo-web/src/components/MessageInput/index.tsx`

- [ ] **10.1** 创建 `GroupPanel` 组件：群列表，显示群名 + 未读计数，点击选中群。从 `useGroupStore` 取数据，`useConversationStore.createConversation(groupId, name, '', 'group')` 创建会话
- [ ] **10.2** `ChatPage` 左侧 Tab 添加 `'groups'`：`'chats' | 'groups' | 'friends'`（三栏）
- [ ] **10.3** 群聊右侧面板：当 `activeConversation.type === 'group'` 时：
  - 标题栏显示群名
  - 消息输入区 `onSendText` 调 `sendGroupMessage` 而非 `sendMessage`
  - CK：打开群聊时调 `sendGroupAck(activePeerId, lastReadSeq)` 替代 `markSeen`
  - 注意：群聊不自动逐条 markSeen，只在上边栏打开群聊时一次性更新游标
- [ ] **10.4** `MessageInput`：群聊模式下禁言检查（可选，第一期跳过）

- [ ] **10.5** 手动验证流程：`npm run dev` → 登录 → 切换到群聊 Tab → 创建群 → 发消息 → 观察推送

- [ ] **10.6** 编译验证：`npx tsc --noEmit`
- [ ] **10.7** Commit: commit "feat: Web UI 群聊 Tab + GroupPanel + 群消息收发"

---

### Task 11: Go Terminal — SDK + TUI

**Files:**
- Modify: `pomelo-terminal-go/pkg/models/models.go`
- Modify: `pomelo-terminal-go/pkg/sdk/client.go`
- Modify: `pomelo-terminal-go/pkg/tui/chat.go`
- Modify: `pomelo-terminal-go/pkg/tui/styles.go`（如需）

- [ ] **11.1** `models.go` 新增：Cmd 常量（`CmdGroupCreateReq = 0x0070` 等）、`GroupInfo` struct、`GroupMsgRecord` struct、`GroupMemberInfo` struct、`GroupNotification` struct
- [ ] **11.2** `client.go` 新增：`SendGroupMessage`, `PullGroupMessages`, `SendGroupAck`, `CreateGroup`, `GetMyGroups`, `GetGroupInfo`, `GetGroupMembers`
  - 方法签名对齐 SDK 已有模式（TCP socket 发送 → channel 接收 response）
- [ ] **11.3** `client.go` — `dispatch()` 新增 C2G_NOTIFY → `EventTypeGroupMessage` 事件
- [ ] **11.4** `chat.go` 新增：
  - **F2 循环**：`好友列表 → 群列表 → 会话列表 → 好友列表 ...`
  - **`renderGroupPanel(h int)`**：群名 + `▶` 选中，按 Enter 进入群聊
  - **群聊模式**：右面板和 C2C 共用消息渲染，区别：消息类型标记 `chatType: "group"`，ACK 游标模式
  - **新命令**：`/create <name>`, `/groups`, `/ginfo <gid>`, `/gmembers <gid>`
- [ ] **11.5** `go build` 编译验证
- [ ] **11.6** Commit: commit "feat: Go Terminal 群聊支持 — SDK + TUI 群列表/群聊收发"

---

## Execution Order

```
Task 1 (Proto) → Task 2 (DB) → Task 3 (Model+Repo) → Tasks 4,5,6 (Services in parallel)
  → Task 7 (Wiring) → Tasks 8,9,10,11 (Web+Terminal in parallel after backend works)
```

Tasks 1-2 are pure foundation. Tasks 4-6 can be done in parallel after Task 3. Task 7 depends on 4-6. Tasks 8-11 can run in parallel after Task 7.
