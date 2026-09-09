# 引用与转发消息设计

- 日期:2026-09-08
- 类型:设计说明（含决策记录，未实施）
- 状态:设计定稿
- 关联:`pomelo-common/src/main/proto/common/common.proto`（MessageContent）、`C2CService`/`C2GService`（写路径）、`PullService`/`GroupPullService`（读路径）、`MinioMediaUrlSigner`（读侧签名）、pomelo-web `MessageBubble`/`MessageInput`/`useChatStore`

## 0. TL;DR

- **引用消息**：**包装型消息**（2026-09-08 定稿）。新增 `MSG_TYPE_REPLY = 9`，content 存 `{reply: 快照, body: 原消息}` JSON；服务端零特判（content 透传 + 签名器递归），无独立存储列。与转发（FORWARD 包装）完全对称。
- **转发消息**：单条转发 = 复用现有发送链路重发原消息 content（媒体复用对象 key，零拷贝，**零协议改动**）；合并转发 = 新增 `MSG_TYPE_FORWARD = 8` + content JSON 携带消息快照数组（卡片样式，点开查看完整记录）。
- 两者共享"快照优先"哲学：渲染不依赖反查，与本项目"拉取即批量列表"的读路径匹配；跳转/溯源需要的信息（message_id）随快照保留。
- 均不动网关与 seqsvr；schema 仅引用需要加列（转发零 schema 变更）。

## 1. 背景与硬约束

现网无引用/转发实现。设计受以下既有链路事实约束（均已在代码中核实）：

| 事实 | 影响 |
|---|---|
| `MessageContent { msgType=1, content=2, timestamp=3, ext=4 }`，无 reply/forward 字段 | 协议需演进；proto3 新增字段向后兼容 |
| 请求 DTO（`C2CRequest.MessageBody` 等）只保留 `(msgType, content)`，ext/timestamp 不落库 | **新字段必须显式接入 DTO 与落库，否则请求→落库链路静默丢失** |
| `content` 字段语义：TEXT=纯文本、媒体=紧凑 JSON（`{key,size,fileName,format,duration,thumb}`） | 引用/转发结构不得塞进 content 破坏语义；新增结构走独立字段或独立 msgType |
| 读路径重建 `MessageContent`（PullService/GroupPullService，元数据经 ext 注入） | 快照需在此组装回协议 |
| Notify（C2CNotify/C2GNotify）的 `message` 即完整 `MessageContent` | 引用/转发随通知天然透传，网关与 seqsvr 零改动 |
| `MinioMediaUrlSigner.signContent` 对媒体消息解析 content JSON，注入 `url`/`thumbUrl` | 合并转发内嵌媒体需要签名器支持嵌套注入（见 §3.3） |
| `MsgType`：TEXT=1…EMOJI=6，**SYSTEM=7 已占用** | 转发类型取 `MSG_TYPE_FORWARD = 8` |
| 消息表按幂等键分区（c2c 按 sender_id / group 按 group_id），`client_msg_id` 幂等 | 引用/转发复用既有发送/重试/幂等链路，无额外处理 |

## 2. 引用消息设计

### 2.1 模型选型：快照式（微信/Telegram），非引用式（Discord/Slack）

| | 快照式 | 引用式（只存 replyToId，渲染反查） |
|---|---|---|
| 渲染 | 一次读取即完整 | 每页列表需按 id 批量反查，被引用消息可能在拉取窗口外/已清理 |
| 原消息清理后 | 摘要仍在（微信同行为） | "消息不存在"占位 |
| 撤回联动（未来） | 需渲染时反查替换"已撤回"占位 | 自动反映 |

本项目离线/历史拉取均为批量列表渲染，快照式避免 N+1 反查。**同时保留被引用消息 `message_id`** 用于点击跳转定位——查得到则滚动高亮，查不到置灰。

### 2.2 协议

```protobuf
message ReplySnippet {
    int64  message_id  = 1;  // 被引用消息服务端雪花 id（跳转定位）
    int64  sender_id   = 2;  // 被引用消息发送者
    int32  msg_type    = 3;  // 占位图标渲染
    string sender_name = 4;  // 展示名快照（服务端覆盖为准）
    string snippet     = 5;  // 摘要文本（定长截断，发送端/服务端双层执行）
    string thumb       = 6;  // 媒体引用封面 key（可选，读侧签名 thumbUrl）
}

message MessageContent {
    ...                     // 1-4 不动
    ReplySnippet reply = 5; // 新增
}
```

不在 `ext`（map<string,string>）承载的原因：弱类型、嵌套结构需二次 JSON 编解码、校验逻辑分散、无法表达"字段存在但为空"。旧客户端解码忽略未知字段 → 降级为不渲染引用块，消息正文照常。

### 2.3 后端

**存储**：`im_message_c2c` / `im_message_group` 各加 `reply_json TEXT NULL`（ReplySnippet 的 JSON）。不采用"只存 reply_id 渲染反查"（见 2.1）；`content` 语义不变。

**写路径**（简化后：客户端快照直存，服务端不做反查）：

```
C2CReq.message.reply{message_id, sender_id, msg_type, sender_name, snippet, thumb}
 → C2CService：reply_json = serialize(reply)（格式透传，不查库）
 → 落库
```

严格模式（可选，建议默认开启）：反查 SQL 携带会话条件（c2c 校验 conversation_id、群校验 group_id），拒绝跨会话引用，防拼接语境钓鱼。

**请求 DTO**：`C2CRequest.MessageBody` / `C2GRequest.MessageBody` 增加 reply 并从 proto 映射——当前链路丢失点，必须显式接入。

**读路径**：`rowToRecord` 携带 reply_json → PullService/GroupPullService 组装时 `mc.setReply(...)`。离线拉取、历史拉取、C2CNotify/C2GNotify 三通道统一携带。

### 2.4 前端

- **发送**：气泡 hover/长按菜单「引用」→ 输入区引用条（`回复 {senderName}：{摘要} ×`）→ `sendText`/`sendMedia` 携带 `reply`（pbcodec `fromObject` 直传）。
- **snippet 生成**（发送端）：TEXT 截 120 字符；IMAGE/EMOJI `[图片]`/`[表情]`；VIDEO `[视频]`（可带 thumb key）；VOICE `[语音] m:ss`；FILE `[文件] 文件名`；FORWARD `[聊天记录]`。
- **渲染**：气泡正文上方引用块（左竖线 + 灰底小字：senderName + 摘要/小封面）；点击按 message_id 本地定位（滚动 + 高亮 2s），内存查不到则不跳转。
- **嵌套引用拍平**：被引用消息自身带 reply 时，生成 snippet 忽略它，只展示一层（微信同行为）。
- **会话列表预览**：显示新消息自身内容，preview 逻辑不变。

### 2.5 边界场景

| 场景 | 处理 |
|---|---|
| 嵌套引用 | 拍平一层 |
| 引用媒体 | snippet 占位 + thumb key（签名器已支持 thumbUrl） |
| 跨会话引用 | 严格模式服务端拒绝；快照展示不受影响 |
| 原消息已清理 | 保留快照落库；跳转置灰 |
| 原消息已撤回（未来） | 渲染时本地反查替换"已撤回"占位；MVP 不处理 |
| 超长摘要 | 客户端 120 字符；服务端覆盖路径统一截断 |
| 旧客户端 | 忽略未知字段，不渲染引用块 |
| 重试/重发 | 复用 clientMsgId 幂等链路 |

## 3. 转发消息设计

### 3.1 两种形态

| | 单条转发 | 合并转发 |
|---|---|---|
| 形态 | 原消息 content 原样重发到目标会话，作为一条普通消息 | N 条消息打包为**一条** `FORWARD` 消息（"聊天记录"卡片） |
| 协议 | **零改动**（复用 TEXT/IMAGE/…现有类型） | 新增 `MSG_TYPE_FORWARD = 8`（`MSG_TYPE_SYSTEM` 已迁移至 99，1–8 段为常规/功能消息预留） |
| 溯源标记 | 无（微信逐条转发同行为）；如需"来自 X 的转发"可后续经 ext 扩展 | 卡片即标记 |
| 媒体 | **复用原对象 key，零拷贝零重传**（对象存储 key 稳定，读侧签名不区分会话） | items 内嵌媒体 key，读侧嵌套签名 |

### 3.2 合并转发协议

content 为 JSON（与媒体消息同一"存 key/快照、不存 URL"的约定）：

```json
{
  "t": "Alice 和 Bob 的聊天记录",
  "n": 3,
  "items": [
    { "msgType": 1, "senderName": "Alice", "text": "…", "ts": 1789000000000 },
    { "msgType": 4, "senderName": "Bob", "ts": 1789000001000,
      "media": { "key": "video/…/v.mp4", "thumb": "image/…/p.jpg", "size": 4897, "fileName": "v.mp4", "format": "mp4", "duration": 65000 } }
  ]
}
```

- items 为**快照**：senderName 取转发时刻显示名；文本放 `text`（单条上限截断），媒体放 `media`（即原消息 MediaContent 去 url/thumbUrl）。
- 外层 MediaContent 复用：`msgType = FORWARD`，`content = 上述 JSON`，`size/fileName` 不使用。协议上无需新增子消息——JSON 承载可变长列表，proto 子消息表达列表嵌套反而繁琐，且与媒体消息 content=JSON 的既有约定一致。
- 上限：items ≤ 50 条、单条 text ≤ 2000 字符、整体 content ≤ 256KB（客户端构建时执行）。

### 3.3 后端：唯一实质改动 = 签名器嵌套注入

- **无 schema 变更**：FORWARD 就是 `msg_type=8` 的一行消息，content TEXT 承载 JSON；seq 分配、信箱、幂等全部复用既有链路。
- `MinioMediaUrlSigner`：`isMediaType` 加入 FORWARD；`signContent` 对 FORWARD 解析 `items[]`，对每项 `media.key → media.url`、`media.thumb → media.thumbUrl` 注入 presigned GET（沿用"永不抛异常、失败原样返回"约定）。**这是合并转发的关键点**：内嵌媒体 key 不经签名则接收方无法播放/展示。
- 逐条转发后端零改动（就是普通消息发送）。

### 3.4 前端

- **入口**：气泡 hover/长按菜单「转发」→ 会话选择器（单聊+群混排、可搜索）；多选模式（长按进入）→ 工具栏「合并转发」。
- **单条转发**：取原消息 content 直接调用目标会话 `sendText`/`sendMedia`（媒体不重传）。
- **合并转发**：构建 items 快照 → `sendMedia({ msgType: FORWARD, content })`；逐条转发的每条走普通发送。
- **渲染**：MessageBubble `case FORWARD` → 卡片样式（📄 聊天记录标题 + 参与者摘要 + 条数）→ 点击弹出全屏详情层，逐条只读渲染 items（复用气泡 Body 渲染器，媒体走 url/thumbUrl）。卡片样式上加转发角标以区分普通消息。
- **会话预览**：`[聊天记录]`。
- **旧客户端**：未知 msgType=8，protobufjs 解码保留数字 → MessageBubble default 分支会显示 JSON 原文，不崩；可在 default 分支对 FORWARD 加"[聊天记录]"占位以体面降级。

### 3.5 边界场景

| 场景 | 处理 |
|---|---|
| 转发媒体 | 复用对象 key，零拷贝；读侧签名与普通媒体一致 |
| 嵌套合并转发（转发一条 FORWARD） | 逐条转发=content 原样重发；合并转发 items 内不允许再嵌 items（拍平为"[聊天记录]"文本项） |
| 被引用的消息被转发 | 逐条/合并 items 只含消息正文，不携带其 reply 块（拍平） |
| 大小超限 | 客户端构建时截断/拒绝（items≤50、text≤2000 字符、content≤256KB） |
| 目标会话校验 | 转发语义即带出，无归属校验；目标会话成员资格由各发送链路既有校验保证（群聊 mute/member 校验复用） |
| 幂等 | 复用 clientMsgId 链路 |

## 4. 引用 × 转发交叉场景

| 场景 | 处理 |
|---|---|
| 引用一条 FORWARD | snippet = `[聊天记录]`（走 msgType 占位规则） |
| 合并转发选中含引用的消息 | items 拍平，不含 reply 块 |
| 在合并转发详情层发起引用 | 以 FORWARARD 卡片整体为引用对象（不引用层内单条），MVP 不支持层内引用 |
| 单条转发到目标会话后再被引用 | 正常：被引用消息就是目标会话里那条新消息 |

## 5. 改动清单与工作量

| 层 | 引用 | 转发 |
|---|---|---|
| 协议 | +MSG_TYPE_REPLY=9（wire 无新字段，快照入 content） | +MSG_TYPE_FORWARD=8；regen 同步 |
| 后端-存储 | 无（content 承载） | 无 |
| 后端-写 | 无（content 透传） | 无（复用发送链路） |
| 后端-读 | content 透传；签名器递归（REPLY→body/reply.thumb、FORWARD→items） | 签名器嵌套注入 |
| 前端-发送 | 引用菜单、引用条、snippet 生成、发送携带 | 转发菜单、会话选择器、多选合并、items 构建 |
| 前端-渲染 | 引用块 + 跳转定位 | FORWARD 卡片 + 详情弹层 + 预览占位 |
| 测试 | 反查覆盖（命中/未命中/伪造/跨会话）、拉取组装 | 签名器嵌套注入、卡片渲染、items 构建上限 |

建议实施顺序：引用（协议→后端→前端）为第一期；合并转发依赖 proto regen 顺路完成，第二期；单条转发纯前端可先行。

## 6. 决策记录

| 决策 | 结论 | 理由 |
|---|---|---|
| 引用模型 | 快照式 + message_id 留痕 | 批量拉取零反查；原消息清理后仍可渲染 |
| 引用摘要可信源 | 客户端快照直存（2026-09-08 简化） | 展示层内容信任客户端；省去写路径多次点查，风险为可伪造展示文案，后置校验可再加 |
| 引用存储 | reply_json 快照列（非 reply_id 反查列） | 读路径零反查，避免 N+1 与窗口外不可见 |
| reply 载体 | MessageContent.reply 子消息（非 ext） | 类型安全、标准 proto 演进 |
| 转发类型值 | MSG_TYPE_FORWARD = 8 | SYSTEM 已迁移至 99，常规标识位 1–8 解耦 |
| 合并转发 content 载体 | JSON（非 proto 子消息列表） | 与媒体消息 content=JSON 约定一致，可变长列表表达自然 |
| 转发媒体 | 复用对象 key 零拷贝 | key 稳定 + 读侧签名不分会话 |
| 逐条转发 | 普通消息重发，无溯源标记 | 对齐微信；需要时再经 ext 扩展 |
