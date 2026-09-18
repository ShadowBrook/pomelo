# 2026-09-18 取消推送广播兜底设计

## 一、动机

原 `PushRouter.push()` 在三种情况下回退到 `publish("gateway.push")` 广播给全部 Gateway 节点：

1. 路由表查不到目标用户（`resolve == null`，即用户不在线）；
2. 路由指向的节点已死（gateway 崩溃后残留路由）；
3. 路由查询本身失败（Redis 异常）。

其中情况 1 占绝对大头：IM 系统的非活跃/离线用户占比很高，**只要接收方不在线就必然广播**。
而广播到达每个节点后做的事是 `sessionRegistry.getConnectionByUserId` → 查不到 → 直接丢弃，
即 N 个节点的 EventBus 消息 + 解码 + 会话表查询全部是纯浪费。离线用户占比越高，
这个放大越明显——把"路由表健康度信号"建立在一条高频正常路径上，本身就是错位。

## 二、为什么可以安全地丢弃（可靠性模型）

本系统的消息可靠性不依赖推送在线率：

- 消息先持久化（`im_message_c2c` / 群消息表），并按收件人信箱分配单调 seq；
- 客户端凭 `PULL_REQ`（`seq > sinceSeq`）做离线/断线补偿同步，ACK 缺口驱动补拉；
- 推送只是**降低在线收信延迟的优化**，丢推送最多增加一次 PULL 往返，不丢消息。

因此：无路由（离线）、死节点、查询失败三种情况的推送直接丢弃都是安全的，
丢弃原因计数即可，无需广播。

### 死节点广播为什么也没用

路由指向节点 X 且 X 已死 ⇒ 目标用户的 TCP/WS 连接随 X 进程消亡 ⇒ **该用户此刻必然离线**，
广播到任何节点都不存在他的连接（`deliverToConnection` 依旧 no-op）。
唯一理论收益是覆盖"路由刚从死节点 X 迁移到 Y、推送读到了迁移前的旧值 X"的竞态窗口——
该窗口极窄（一次 map put），且同样由 PULL 补偿，不值得为它保留 N 倍放大的广播路径。
死节点残留路由仍由 `PushRouter` 惰性条件清理（`unregister(userId, deadNodeId)`，
仅当条目仍指向该死节点时移除），保证后续推送走 `no_route` 而不是反复探测死节点。

## 三、改动内容

### PushRouter（pomelo-logic-server）

| 场景 | 原行为 | 新行为 |
|---|---|---|
| 集群 + 路由命中且节点存活 | 精确路由 `send("gateway.push.<nodeId>")` | 不变 |
| 集群 + 无路由（离线） | 广播 | 丢弃，计数 `dropped/no_route` |
| 集群 + 死节点 | 广播 + 惰性清理路由 | 丢弃，计数 `dropped/dead_node`，仍惰性清理 |
| 集群 + 路由查询失败 | 广播 | 丢弃，计数 `dropped/lookup_failed` |
| 非集群（单进程） | 广播 `publish("gateway.push")` | **本地点对点** `send("gateway.push")`，计数 `local` |

- 单进程模式不再用 publish：Vert.x EventBus 单进程内本就支持点对点 send，
  单 JVM 只有一个 gateway dispatcher，send 语义（恰好一个消费者）比广播更准确；
  跨进程非集群部署（gateway/logic 分属两个 JVM）原本广播就到不了对方 EventBus，
  行为不变（均不可达，`ClusterHelper.warnIfNotClustered` 有告警）。
- 路由可用性判断收敛到 `SessionRouteTable.isRoutingAvailable()`（集群模式且 CM 就绪），
  PushRouter 与 MessageDispatcher 的 consumer 注册共用同一判定，两侧地址永远配对。
- 指标 `im.push.delivery.total` 的 tag key 统一为 `{mode, reason}`：
  `mode=precise|dropped|local`，`reason=none|no_route|dead_node|lookup_failed`。
  不适用 reason 的路径固定 `none`——Prometheus 要求同名指标 tag key 一致，
  混用会导致后注册的序列注册失败（实施中发现并修复）。

### MessageDispatcher（pomelo-gateway）

按 `isRoutingAvailable()` 条件注册 consumer：集群只订阅 `gateway.push.<nodeId>`，
单进程只订阅 `gateway.push`；不再订阅广播地址。
滚动发布期间若旧版 logic-server 仍广播，新版 gateway 不接收——被丢的推送由客户端
PULL 补偿，无消息丢失；建议先升 gateway 再升 logic。

## 四、监控与告警

- `ImPushDegradedBroadcast`（broadcast > 0）删除：`no_route` 是离线用户的正常现象，
  按次告警必然误报。
- 新增 `ImPushDroppedDeadNode`（dropped/dead_node > 0）：gateway 节点疑似崩溃的信号。
- 新增 `ImPushRouteLookupFailed`（dropped/lookup_failed > 0）：路由存储/Redis 异常信号。
- 路由表健康度观察改为看 `no_route : precise` 比例的**异常跳变**（如 2026-09-07
  订阅丢失类事故会导致路由批量消失、no_route 比例骤升），属面板/容量层关注，不做单次告警。

## 五、测试

`PushRouterTest` 覆盖五条路径：单进程本地投递、精确路由（且断言不再触碰广播地址）、
无路由丢弃、死节点丢弃+条件清理、查询失败丢弃。假路由表以子类覆写
`resolve/isNodeAlive/unregister/isRoutingAvailable` 注入（`SessionRouteTable` 去除 final）。
