# 消息模型与会话键设计：单聊 conversation_id 与群聊 group_id

- 日期:2026-09-08
- 类型:设计说明（决策记录）
- 状态:现行设计
- 关联:`db/schema.sql`（im_message_c2c / im_message_group）、`PullService`、`GroupPullService`、`C2CService`、`C2GService`

## 0. TL;DR

- `im_message_c2c.conversation_id` **不是冗余列**：它是单聊会话历史读路径的索引键，承担方向归一化与索引序分页两个职责。
- `im_message_group` **不需要 conversation_id**：`group_id` 本身就是群时间线的自然会话键，加列只是冗余副本。
- 两者差异的根源是消息扩散模型不同：**单聊 = 写扩散信箱（收件人维度 seq），群聊 = 读融合时间线（群维度 seq）**——微信系标准模型。
- 未来的混合会话列表在列表层合成统一会话键（`c2c:<min>:<max>` / `group:<groupId>`），不需要动任何消息表。

## 1. 背景

单聊消息表带 `conversation_id`（`min(sender,recipient):max(sender,recipient)` 的归一化字符串），群消息表没有对应列。一个问题自然浮现：单聊这个列是不是冗余的（可以由 sender/recipient 派生）、群聊是不是缺了它？本文记录该设计的原因与边界。

## 2. 两种消息扩散模型

| | 单聊（im_message_c2c） | 群聊（im_message_group） |
|---|---|---|
| 扩散模型 | 写扩散信箱 | 读融合时间线 |
| 时间线键 | `conversation_id`（min:max 方向归一化） | `group_id`（天然唯一） |
| seq 语义 | 收件人信箱序号：`seqsvr.fetchNextSequence(recipientId)`，每人独立单调水位 | 群时间线序号：`seqsvr.fetchNextSequence(groupId)`，全群共享一条流 |
| 历史拉取 | `WHERE conversation_id = $1 ORDER BY created_at DESC, id DESC` | `WHERE group_id = $1 AND seq > cursor ORDER BY seq` |
| 已读模型 | 消息级 `status`（0 发送 / 1 送达 / 2 已读），按消息 ACK | 成员级 `im_group_member.last_read_seq`，一条水位线 |
| 存储特征 | 每条消息一行，收件人视角拉取 | 每条群消息一行（所有成员读同一行） |

群消息按成员写扩散（每成员一份副本）会让存储随群规模平方膨胀，所以群聊用公共时间线 + 成员各自已读水位，这是行业共识。两张表的键都恰好是该模型下"会话的最小自然键"，多一列都是冗余。

## 3. 单聊:conversation_id 为什么必须存在

### 3.1 方向归一化

一个单聊会话由两条有向消息流组成：A→B 与 B→A，落库为两个不同的 `(sender_id, recipient_id)` 组合，且按 `sender_id` 哈希分布在不同分区。用户感知的"我和 B 的聊天记录"是一条完整时间线。`conversation_id = min:max` 把方向归一，历史拉取用单键即可取回双向消息。

### 3.2 索引序分页（关键工程原因）

会话历史是单聊两大热读路径之一（另一条是收件箱按 seq 的离线拉取）。有该列时：

```sql
WHERE conversation_id = $1 ORDER BY created_at DESC, id DESC LIMIT 50
-- 走 idx_c2c_conversation (conversation_id, created_at DESC, id DESC)，索引序流式返回，扫满 LIMIT 即停
```

若去掉列、由 sender/recipient 现场推导，谓词退化为双向 OR：

```sql
WHERE (sender_id = $a AND recipient_id = $b) OR (sender_id = $b AND recipient_id = $a)
ORDER BY created_at DESC, id DESC LIMIT 50
```

PostgreSQL 对此类 OR 只能 BitmapOr 取出两个方向的**全部**匹配行后再排序——LIMIT 无法提前终止，长会话翻页每页都全量物化。要恢复流式分页须改写为两条带 LIMIT 的 UNION ALL 并在应用层归并，复杂度显著上升。

### 3.3 分区与分片

- 表按 `sender_id` HASH 分区，会话消息天然横跨两用户的分区，**任何**方案都得不到分区裁剪——此时"存储列 + 复合索引"就是唯一干净的解法。
- 未来若按用户进一步分片，跨分片的会话查询需要一个与分片无关的逻辑键，`conversation_id` 正好充当。

### 3.4 会话级功能的前瞻

会话列表、每会话未读数、会话最后一条消息（`GROUP BY conversation_id`）等 IM 必备能力，有存储列就是普通索引聚合；没有则每条 SQL 都要计算 min/max 对。

### 3.5 曾考虑的替代方案

把字符串列换成两个 BIGINT `(conv_a, conv_b)`（LEAST/GREATEST），谓词变单条等值、略省空间。行为完全等价，但要动 schema + 迁移 + 读写两侧，无功能收益——**决策：维持现状**。

## 4. 群聊:group_id 即会话键

群聊所有成员读写同一条公共时间线，`group_id` 就是会话键本身，无方向需要归一化。为其加 `conversation_id` 只会得到 `group_id` 的冗余副本。群拉取路径（`GroupPullService` → `pullMessages`）已经按 `group_id` + seq 游标走 `idx_group_conversation (group_id, created_at DESC)`，读路径完整。

## 5. 边界与注意点

- `conversation_id` 仅用于服务端查询，**不下发协议**（PullResp 的 ext 不含它），客户端不需要感知。
- 群表 seq 注释标注"设计保留，群消息暂未接入"已过时——C2G 链路已接入群维度 seq 发号，勿再据此省略。
- 消息表均已按幂等唯一键分区（c2c 按 `sender_id`、group 按 `group_id`，见 `uq_c2c_client_msg` / `uq_group_client_msg`），重试幂等依赖约束内含分区键，调整分区策略时必须一并评估。

## 6. 演进:统一会话列表

客户端首页的"最近会话列表"（单聊+群聊混排）未来实现时：

1. 列表层合成统一会话键：单聊 `c2c:<min>:<max>`、群聊 `group:<groupId>`；
2. 每用户一份会话摘要状态（最后一条消息、未读数、置顶等），由发送/已读链路异步更新；
3. 不修改任何消息表结构。

## 7. 决策记录

| 决策 | 结论 |
|---|---|
| c2c 的 conversation_id 是否移除 | 保留——热读路径的索引键，移除导致 OR 全量排序退化 |
| 群表是否补 conversation_id | 不补——`group_id` 已是自然会话键 |
| 字符串格式是否改 (conv_a, conv_b) | 不改——零功能收益，不值得迁移成本 |
| 统一会话列表的实现位置 | 列表层合成会话键 + 独立摘要状态，不动消息表 |
