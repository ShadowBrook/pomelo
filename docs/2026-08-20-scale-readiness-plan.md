# 实例快速 Scale 就绪方案

> 状态：设计文档（方案 + 权衡 + 分阶段），**尚未实施**。
> 目标拓扑：同时兼容 K8s 自动扩缩容 与 Docker Compose / 裸机手动扩容。
> 范围：Snowflake workerId、seqsvr 扩容重排 + Store 高可用、PG/Redis 高可用、Session 路由/广播兜底。

---

## 1. 背景与目标

Pomelo 已具备一套横向扩展底座：Gateway 层的 `SessionRouteTable` 分布式路由 + 节点心跳 + 广播兜底，seqsvr 的 Store/Alloc/Mediate 三段式号段租约。但在「快速 scale」（频繁增删实例）场景下，有 6 个点会导致正确性问题、可用性抖动或容量打爆。本文档逐一对每个点给出方案对比、推荐与权衡，并排出分阶段落地顺序。

**核心约束（贯穿全文）**：seqsvr 的序列号「永不回退」是硬约束，任何 HA 改造都不得破坏该语义。

---

## 2. 现状摘要（已具备，扩容前需知）

| 组件 | 现状 | 对扩容的意义 |
|---|---|---|
| Session 路由 | `SessionRouteTable`（cluster-wide map，userId→nodeId）+ `live-gateways` 节点心跳 TTL + `PushRouter` 精确路由/广播兜底 | Gateway 可水平扩，已基本就绪 |
| Session 注册 | `SessionRegistry`（每 Gateway 进程内 `ConcurrentHashMap`） | 进程内，无共享状态，天然可扩 |
| seqsvr 分配 | `AllocManager` 号段租约（卸载立即 / 新增 pending 5s / `ROUTE_OUTDATED` 重试） | 迁移机制已正确，问题是「分配策略」 |
| seqsvr 路由表 | `MediateManager.generateRouter()` 全网均分 | 增减节点触发全网重排（见 §4.2） |
| seqsvr 存储 | `StoreManager` 单机 mmap + `force()` | 单点 + 冷启动全量拉取（见 §4.3） |
| 集群管理器 | `vertx-redis-clustermanager`（gateway/logic/common 统一） | EventBus + 路由 map + 心跳全压一个 Redis |
| ID 生成 | `SnowflakeIdGenerator`（用户/消息 ID）+ `RedisIdGenerator`（服务端 seq） | workerId 硬编码 + 单进程双实例（见 §4.1） |
| 在线状态 | `RedisOnlineStatus`（`im:online:users` set）+ `SessionRouteTable` 两份 | 双份真源，需统一（见 §4.5） |

---

## 3. 全局设计原则（K8s 与 Compose 兼容）

1. **实例标识来源统一走「环境变量优先，注册中心兜底」**。K8s 用 Downward API 注入，Compose 用 `environment:` 注入，两者都拿不到时才回退到 Redis 租约。避免为两种拓扑写两套代码。
2. **有状态信息（workerId、nodeId、号段）必须能在实例重启后重新获得**，不能依赖「实例永续」假设。K8s Pod 会漂移、Compose 容器会重建。
3. **凡涉及持久化，必须显式声明耐久性等级**（seq 永不回退 > 在线状态可重建 > 路由表可重生成），据此选择存储，避免用「可重建」的存储去扛「不可回退」的数据。
4. **单点消除的优先顺序**：正确性 → 可用性 → 容量。先修会出错/会抖的，再修会打爆的。

---

## 4. 六大问题逐一分析

### 4.1 Snowflake workerId 唯一性（正确性，最高优先级）

**现状 / 问题**

- `conf/config.yaml:36-37` 硬编码 `snowflake.workerId: 1`。
- `LogicVerticle.java:47-48` 与 `ApiVerticle.java:77-78` 各自 `new SnowflakeIdGenerator(ConfigHolder.getInt("snowflake.workerId", 1))`。
- **两个独立问题**：
  1. **跨实例**：多实例都读 workerId=1 → 撞 ID。
  2. **单进程内**：`LogicMain.java:28-29` 在同一个 JVM 部署 `LogicVerticle` + `ApiVerticle`，两个 `SnowflakeIdGenerator` 实例共用同一 workerId，各自维护独立的 `sequence`/`lastTimestamp`。同一毫秒内首次调用，两边都返回 `(ts-EPOCH)<<22 | workerId<<12 | 0`，**必然撞 ID**——这是当前就存在的隐患，扩容只会放大。

`SnowflakeIdGenerator` 本身已做好：workerId 范围校验（0–1023）、时钟回拨 ≤5s 等待 / >5s 抛异常。缺的是「workerId 从哪来」和「单进程内唯一」。

**方案对比**

| 方案 | 说明 | 优点 | 缺点 |
|---|---|---|---|
| A. 环境变量注入 | 每实例设 `SNOWFLAKE_WORKER_ID` | Compose 简单直接 | K8s 手工维护易错，扩到几十个就失控 |
| B. K8s StatefulSet ordinal | 从 `POD_NAME` 解析序号当 workerId | K8s 原生 | Compose 不适用，需两套逻辑 |
| C. Redis 租约分配 | 启动时 `INCR`/`SETNX` 领 [0,1023] 的 workerId，优雅下线释放 | 兼容两者，自动防冲突 | 引入租约/回收逻辑；workerId 耗尽需处理 |
| D. Provider 抽象（推荐） | `WorkerIdProvider`：先读 env，缺省走 Redis 租约 | 一套代码兼容两种拓扑 | 比 A/C 略多一点代码 |

**推荐：D + 单进程共享实例**

1. 抽象 `WorkerIdProvider`（`interface { int resolve(Vertx) }`）：实现 `EnvWorkerIdProvider`（读 `SNOWFLAKE_WORKER_ID`）与 `RedisLeaseWorkerIdProvider`（启动租约、下线释放），按「env 有值则用 env，否则 Redis」组合。
2. **单进程只建一个 `SnowflakeIdGenerator` 实例**，通过依赖注入传给 `LogicVerticle` 和 `ApiVerticle`，消除同 workerId 双序列碰撞。这一步是独立修复，不依赖扩容。
3. workerId 上限 1023 需纳入容量规划：若单集群实例数可能超过 1024，需评估 snowflake 位分配（见 §7 待核实项）。

**落地要点 / 权衡**

- 改 `LogicMain` 启动时先解析 workerId，再注入两个 verticle。
- Redis 租约方案需定义租约 TTL 与续期（进程存活即续期），避免僵尸 workerId 占位导致耗尽。
- 时钟回拨在 K8s 更常见（NTP 漂移），现有 ≤5s 等待逻辑已覆盖大部分情况；建议容器内强制 NTP。

---

### 4.2 seqsvr 扩容重排（可用性抖动）

**现状 / 问题**

`MediateManager.generateRouter()`（`MediateManager.java:144-176`）用 `chunk = sectionCount / n` 把**全部 section 按存活节点数重新均分**。任何节点增删都让**所有**节点的号段边界变化：

- 被收回号段：`AllocManager.applyRouter()` 立即卸载（`unloadSections` 连 `curSeqs` 一起清）。
- 新增号段：pending 5s 后才生效。
- 期间该号段无人服务，客户端靠 `ROUTE_OUTDATED` 重试兜底。

快速 scale 时，全网重排会持续制造「卸载/pending 窗口」与 `curSeqs` 大批失效（非正确性问题——seq 以持久化 `sectionMaxSeqs` 起步，不会回退——但会瞬时不服务 + 抖动）。

**方案对比**

| 方案 | 说明 | 优点 | 缺点 |
|---|---|---|---|
| A. 增量 rebalance（推荐） | 维护「当前 assignment」，节点增减时只迁移最少量的 section（新节点从最重节点各取一段 / 缩容时把下线节点号段摊给存活节点） | 迁移量 O(1/N)，抖动最小；复用现有 lease/pending/重试机制 | MediateManager 需改为有状态增量计算 |
| B. 一致性哈希 | 用哈希环分配号段 | 迁移量小 | 破坏 section 连续性，与 `RangeId`/`sectionIndex` 模型冲突，改动大 |
| C. 批量扩缩容 | 一次加 N 节点只做一次 rebalance | 减少重排次数 | 仍是全网重排，治标不治本 |

**推荐：A + rebalance 阈值 + 批量**

**量化（为什么必须增量）**：生产空间 `S ≈ 21475` 段（`PRODUCTION_MAX_ID_SIZE / SECTION_SIZE`）。N=4→5 时：

- **增量**：只动新节点应得的 `S/(N+1) = 4295` 段（约 20%），且只在「存量节点 → 新节点」一个方向。
- **全网均分**：约一半号段换主人（≈10739 段，50%），其中有大量「存量节点之间」的无谓迁移——例如 `[4295,5369)` 从 node0 挪到 node1，两个都不是新节点。

差距不在「多挪几段」，而在存量节点也被迫反复 unload/reload + 清空 `curSeqs`，快速 scale 时永不消停。

**数据模型已支持，无需改协议**：

- `RouterNode.sectionRanges` 是 `List<RangeId>`（`RouterNode.java:14`），一个节点本可拥有多个不连续号段——只是现算法给每节点只塞一个连续 `RangeId`。
- `AllocManager.coveredSections()`（`AllocManager.java:448`）与 `SeqClientService.owningNode()`（`SeqClientService.java:142`）都遍历多 RangeId，消费端无需改。

**delta 迁移算法**：

```
generateIncrementalRouter(aliveNodes):
    current = 当前 assignment            # Map<nodeId, List<RangeId>>
    target  = S / N                       # remainder 分摊到前几个节点
    donors    = { node : count > target } # 超出量降序
    receivers = { node : count < target } # 缺额降序（含新节点）
    while donors 非空 且 receivers 非空:
        d = 最超载 donor；r = 最欠载 receiver
        k = min(d 超出段数, r 缺额段数)
        从 d 尾部割 k 个连续 section 追加到 r
    return Router(version+1, 新 nodeList)
```

- 加节点：新节点是唯一 receiver，每存量节点割尾 `S/(N·(N+1))` 段给它 → 新节点得到 N 段碎片。
- 缩容/宕机：`unregister()` / 心跳超时把下线节点号段摊给最欠载者。
- 稳定化：rebalance 阈值（`max - min ≤ threshold` 不触发）+ 批量注册（一个租约窗口内的多个 register 合并成一次重排）。

**迁移正确性机制已就绪（不动）**：

- `applyRouter`（`AllocManager.java:242`）：removed 立即卸载 / added pending 5s，对增量迁移同样成立。
- `SeqClientService` 的 `ROUTE_OUTDATED` 重试（`SeqClientService.java:74`）在迁移窗口兜底。
- seq 不回退：卸载清 `curSeqs`，重新激活从持久化 `sectionMaxSeqs` 起步。

**两个必须补的状态缺口（真正的新工作量）**：

1. **MediateManager 重启丢 assignment**：构造器 `router = new Router(0, emptyList)`（`MediateManager.java:65`），`MediateVerticle.start()` 不回填。需启动时 `store.loadRouteTable()` 载入基准；节点 re-register 时 reconcile（旧 router 里未 re-register 的节点短暂保留号段，或按死节点重排）。
2. **`saveRouteTable` 是 fire-and-forget**：`regenerateAndPersist()`（`MediateManager.java:138`）失败只 `LOG.warn`。增量的 assignment 基准依赖这份持久化，必须改成 await + 重试。

**碎片化 / defrag 权衡**：每存量节点割尾会让新节点拿到 N 段碎片，多次 scale 后碎片累积（路由表从「每节点 1 段」涨到「若干段」）。数据模型支持，`owningNode` 线性扫可忽略。建议**接受碎片** + 把 defrag 做成显式运维动作（低峰一次性全量重排收尾），不自动触发。

---

### 4.3 seqsvr Store 单点 / 高可用（SPOF）

**现状 / 问题**

`StoreManager`（`StoreManager.java:29-97`）是单机 mmap 文件 + 路由表 JSON，`saveMaxSeq` 每次 `force()`。问题：

1. **单点**：Store 挂，所有 AllocSvr 租约 5s 到期后 `checkLease()` 全体 `state=ERROR` 停发号（`AllocManager.java:202-215`），全局停摆。
2. **冷启动放大**：新 AllocSvr `loadMaxSeqsData()` 拉全量 section 数组，快速加一堆节点会把 Store 并发打爆。

**方案对比**

| 方案 | 说明 | 优点 | 缺点 |
|---|---|---|---|
| A. 多副本 Store + NRW（推荐） | 部署 M 个 StoreVerticle，客户端走 `ReplicatedStoreClient`（w/r 仲裁） | **端到端已实现**，只需部署 + 配置一致性校验 | 3× 存储；saveMaxSeq 有仲裁 RTT；共享地址双注册需约束 |
| B. 换共享存储（Redis AOF） | 把 maxSeqs/路由表改存 Redis（AOF） | 简化运维，Redis 已在栈内且会做 HA（§4.5） | **耐久性从 mmap force() 降级**，需评估 seq 复用风险 |
| C. Raft 组（etcd） | 引入 etcd 存号段 | 强一致 | 引入新组件，过重 |

**推荐：A（已实现，直接部署）；B 作为中长期简化路径**

- NRW 链路已完整落地，**不是待开发功能**：
  - `StoreVerticle.start()`（`StoreVerticle.java:55-64`）同时注册共享地址 `seqsvr.store.*` 与副本专属地址 `seqsvr.store.<replicaId>.*`。
  - `SeqAllocVerticle`（`SeqAllocVerticle.java:64-65`）与 `MediateVerticle`（`MediateVerticle.java:53-56`）都经 `StoreClients.create(..., storeReplicas, storeW, storeR)` 选择单副本或 NRW。
  - `ReplicatedStoreClient`（`ReplicatedStoreClient.java`）已实现 W 写仲裁 / R 读仲裁 + max 合并。
  - 配置项 `seqsvr.store.replicas / w / r` 已就绪，默认 `w=2, r=2`。
- 中长期若接受 Redis AOF 耐久性，用 B 简化（Store 与在线状态、ID、路由共用一套已 HA 的 Redis）。

**正确性论证（为什么 max 合并是对的）**

- **仲裁数学**：M=3、W=2、R=2 → `W + R = 4 > M = 3`，读写仲裁必重叠，读到的最新写必在 max 合并中胜出。
- **为什么 max 而非 last-write-wins**：seq 单调递增，且租约保证「任意 section 任意时刻只有一个写者」（removed 立即卸载 / added pending 5s，见 §4.2）。副本间无并发写冲突，max = 真值。
- **fail-safe 是设计的一部分**：2/3 副本挂时仲裁无法满足 → `checkLease()` 5s 后 `state=ERROR` 停发号，宁可停摆不冒 seq 复用。

**部署要点（M=3）**

- 3 个 `StoreVerticle`，`replicaId=r1/r2/r3` 唯一，**`dataDir` 必须不同**——`StoreManager` 是 mmap 单文件，两个副本共用 dataDir 等于 map 同一个文件，是伪副本。
- AllocSvr 与 MediateSvr 两侧都要设：
  ```
  seqsvr.store.replicas=seqsvr.store.r1,seqsvr.store.r2,seqsvr.store.r3
  seqsvr.store.w=2
  seqsvr.store.r=2
  ```

**footgun（必须约束）**：`StoreVerticle` 每个副本都注册共享地址 `seqsvr.store.*`。若某侧漏设 `replicas` 误走单副本 `EventBusStoreClient`，其写会经共享地址被 EventBus round-robin 到某个副本，只写 1 份，副本静默分叉且无人报错。缓解二选一：

- 有副本运行时不下发共享 consumer（共享地址仅在单副本模式下注册）；或
- 启动校验：`replicas` 非空则强制两侧都用 `ReplicatedStoreClient`，单副本 client 拒连多副本拓扑。

**验证清单**

- 跑现有 `SeqSvrNrwIntegrationTest`。
- 混沌三连：① kill 1 副本 → 仲裁仍满足，seq 单调不回退；② kill 2 副本 → AllocSvr 租约到期停发号（fail-safe 生效）；③ 恢复副本 → 租约恢复、重载 maxSeqs、继续发号。
- 单写者假设回归：构造迁移窗口（removed/pending 交界），断言同一 section 无并发双写。

**落地要点 / 权衡**

- 3× 存储 + 每次 `saveMaxSeq` 仲裁 RTT；但 `bumpSection` 是异步 `saveMaxSeq`（`AllocManager.java:397`），不阻塞发号。
- 冷启动放大：NRW 下 `loadMaxSeqsData` 是 R 次全量读取，快速加节点更重 → 需配合分段/流式加载或本地缓存（见 Phase 1.3）。
- 硬约束：seq「永不回退」不能破——A 靠仲裁多数派 + 单写者；B 靠 Redis AOF `everysec` 起步并显式确认宕机丢失窗口。

---

### 4.4 PG 连接池总量（容量）

**现状 / 问题**

`conf/config.yaml:26-28` `maxSize: 10`、`maxWaitQueueSize: 50`。每扩一个 logic-server 就 +10 个 PG 连接，线性打爆 PG `max_connections`。此前 `maxWaitQueueSize 512→5000` 只是把「连接排队失败」变成「高延迟排队」，并未增加真实容量。

**方案对比**

| 方案 | 说明 | 优点 | 缺点 |
|---|---|---|---|
| A. PgBouncer 前置（推荐 K8s） | app → PgBouncer（transaction pooling）→ PG | 实例数与 PG 连接解耦 | 多一个组件；需评估 prepared statement/事务语义 |
| B. 收紧每实例池 + 容量公式（Compose 简单路径） | 每实例小池，总连接 = 实例数 × maxSize ≤ PG max_connections | 无新组件 | 扩容仍要手动算连接预算 |
| C. 读写分离 | 读路径（PullMessageHandler）走只读副本 | 减轻主库压力 | 本期非必需 |

**推荐：A（K8s）+ B（Compose）**

1. **容量公式**：`实例数 × maxSize × (1 + 冗余) ≤ PG max_connections - 预留`。扩容前先算，别等到打爆。
2. `maxWaitQueueSize` **不应继续放大**：它只决定「爆了之后是报错还是排队」，正确做法是加容量（连接数或 PgBouncer），不是加队列。
3. K8s 多实例下优先 A；Compose 少实例用 B 即可。

**落地要点 / 权衡**

- PgBouncer 用 transaction pooling 时，需确认 `PgPool` 是否依赖 session 级状态（如 `SET` 变量、临时表）。当前用法看是普通 SQL，兼容性高。
- 读路径（`PullMessageHandler` 的离线拉取/历史拉取）是 PG 高负载来源，未来可单独上只读副本，本期不做。

---

### 4.5 Redis 单点（多职责叠加）

**现状 / 问题**

一个 Redis 同时承担（`conf/config.yaml:11-18,34`）：

1. `vertx-redis-clustermanager`：EventBus 集群 + cluster-wide map（session 路由 + live-gateways 心跳）。
2. `RedisIdGenerator`：`seq:id:generator` key。
3. `RedisOnlineStatus`：`im:online:users` set（在线状态查询）。
4. （若 §4.3 走 B）seqsvr Store。

单点挂了，EventBus、session 路由、ID 生成、在线状态全停。**爆炸半径是全局**。

**方案对比**

| 方案 | 说明 | 优点 | 缺点 |
|---|---|---|---|
| A. Redis Sentinel（推荐） | 单逻辑 Redis + 哨兵 failover | HA，兼容 `vertx-redis-clustermanager` 单 endpoint | 仍是单主，写吞吐有上限 |
| B. Redis Cluster | 分片 | 写吞吐高 | cluster manager 对 hash-slot 模式兼容性存疑，风险高 |
| C. 职责隔离 | 不同 Redis 实例分管 cluster manager / ID / 在线 / store | 单点爆炸半径缩小 | 运维复杂度高 |

**推荐：A（Sentinel）+ 文档化爆炸半径；可选 C 隔离**

1. 先上 Sentinel 消除单点；`redis.cm.endpoint` 指向 sentinel 的 failover 地址。
2. 记录「一个 Redis 扛四件事」的爆炸半径，评估是否值得用 C 把 cluster manager（关键路径）与 ID/在线状态（可重建）隔离。
3. **在线状态双份真源需统一**：`RedisOnlineStatus`（`im:online:users`）与 `SessionRouteTable`（cluster map）都在表达「谁在线」，二者若不一致会导致 push 路由与 presence 查询打架。建议明确：**push 路由以 `SessionRouteTable` 为准，`RedisOnlineStatus` 仅作 presence 查询缓存**，并统一写入/清理时机。

**落地要点 / 权衡**

- Sentinel 对 `vertx-redis-clustermanager` 的适配需确认（它需要 redis 支持 pub/sub + kv；sentinel 主从切换会短暂影响，需验证客户端重连）。
- `RedisIdGenerator` 的 `seq:id:generator` key 是全局递增号段，Sentinel 主从切换期间若主挂了，需保证已分配号段不重复（Lua 脚本 `INCRBY` 在主上执行，切换后从可能落后，需评估号段回退风险）。

---

### 4.6 Session 路由 / 广播兜底（已基本就绪，收尾硬化）

**现状 / 问题**

`SessionRouteTable` + `PushRouter` 精确路由 + 广播兜底已经到位。剩余三处小问题：

1. **非集群模式静默退化**：`SessionRouteTable` 在 `!vertx.isClustered()` 时全部 no-op（`register/resolve` 直接返回空，见 `SessionRouteTable.java:68-70,110-112,170-172`）。多实例若误以非集群模式启动，`PushRouter.resolve()` 恒 null → 每次推送全广播，且无人察觉。
2. **死节点路由惰性清理**：死节点路由靠 `PushRouter.push` 发现死节点时惰性 `unregister`，无主动清理。快速 scale 的节点 churn 会让广播量上升，每个 gateway 都要过滤无关用户的 push。
3. **广播放大**：路由 miss / 节点刚死都 `publish("gateway.push")` 打给所有 gateway。

**推荐：启动断言 + 可选主动清理**

1. **启动断言**：多实例部署（非本机单进程）时，若 `!vertx.isClustered()` 直接 fail-fast 或打 error 日志，避免静默退化。这是低成本高收益的防御。
2. **主动清理（可选）**：监听集群 membership / 复用 live-gateways 心跳超时，批量清掉死节点残留路由（`SessionRouteTable` 已有 `getOnlineUserIds()` 供节点下线清理，可扩展到主动 sweep）。
3. 广播兜底本身正确，保留；仅监控广播量，出现异常放大再优化。

**落地要点 / 权衡**

- 广播兜底是正确性保障（路由 miss 不能丢消息），不要为省广播而删掉它；优化方向是「减少需要广播的场景」（主动清理死节点路由），而非「去掉广播」。

---

## 5. 分阶段落地路线图

按「正确性 → 可用性 → 容量 → 硬化」排序，每阶段可独立交付、独立验证、独立回滚。

### Phase 0 — 正确性（先改，否则扩容就出错）

| 项 | 内容 | 依赖 |
|---|---|---|
| 0.1 | 单进程共享一个 `SnowflakeIdGenerator` 实例（修单进程内双序列撞 ID） | 无 |
| 0.2 | `WorkerIdProvider`（env 优先 + Redis 租约兜底），注入两个 verticle | 0.1 |

**验证**：并发压测注册 + 发消息，断言全量 ID 无重复。

### Phase 1 — 可用性（seqsvr 扩容不抖、不单点）

| 项 | 内容 | 依赖 |
|---|---|---|
| 1.1 | `MediateManager` 增量 rebalance（delta 迁移 + 阈值 + 批量）+ 补重启 assignment 回填与 `saveRouteTable` 可靠性 | Phase 0 |
| 1.2 | Store 多副本 NRW（已实现，部署 + 配置一致性校验 + 混沌验证） | 无（与 1.1 可并行） |
| 1.3 | `loadMaxSeqsData` 分段/流式加载，缓解冷启动放大 | 1.2 |

**验证**：扩容/缩容期间压测发号，断言 0 错误、seq 单调不回退、抖动窗口可测。

### Phase 2 — 容量与单点（基础设施）

| 项 | 内容 | 依赖 |
|---|---|---|
| 2.1 | PG 连接预算（K8s 上 PgBouncer，Compose 收紧每实例池 + 容量公式） | 无 |
| 2.2 | Redis Sentinel 高可用 + 在线状态双源统一 | 无 |
| 2.3 | 评估 cluster manager / ID / store 是否职责隔离（可选） | 2.2 |

**验证**：Redis 主从切换演练、PG 连接数监控告警、在线状态一致性测试。

### Phase 3 — 硬化（收尾，可选）

| 项 | 内容 | 依赖 |
|---|---|---|
| 3.1 | 非集群模式启动断言 | 无 |
| 3.2 | 死节点路由主动清理 + 广播量监控 | 3.1 |

**验证**：模拟节点宕机，观察路由清理时延与广播量回落。

---

## 6. 风险与回滚

| 风险 | 影响 | 缓解 / 回滚 |
|---|---|---|
| 改 `generateRouter` 引入分配 bug | seq 分配错乱 | 增量算法严格单测 + 与旧全网均分结果做一致性对照；回滚到旧 `generateRouter`（迁移机制不变） |
| Store 换存储破坏 seq 不回退 | 消息 seq 复用，客户端同步错乱 | 任何存储方案必须先过「宕机回退」测试；mmap→Redis 时 AOF 策略显式确认 |
| Redis 主从切换导致 cluster manager 抖动 | EventBus / 路由短暂不可用 | Sentinel 切换演练；client 重连验证 |
| PgBouncer 与 `PgPool` 语义不兼容 | 查询失败 | 先在 staging 跑全量消息流验证 transaction pooling |
| 单进程共享 generator 改造遗漏某调用点 | 局部仍撞 ID | grep 全量 `new SnowflakeIdGenerator` 收敛为单例 |

---

## 7. 待核实清单（实施前必须确认）

1. **NRW 配置一致性 + 混沌验证**：`ReplicatedStoreClient` 已实现（§4.3）；实施前确认 AllocSvr/MediateSvr 两侧 `seqsvr.store.replicas` 一致、各副本 `dataDir` 独立，并跑 kill 1/kill 2 副本混沌测试（见 §4.3 验证清单）。
2. **`RedisOnlineStatus` 与 `SessionRouteTable` 的一致性现状**：确认二者写入/清理是否已同步，是否存在已知不一致。
3. **`vertx-redis-clustermanager` 对 Sentinel / 主从切换的支持**：确认 failover 期间 EventBus 与 cluster-wide map 行为。
4. **`RedisIdGenerator` 在主从切换下的号段回退风险**：`seq:id:generator` 的 Lua 递增在切换后是否会重复分配。
5. **workerId 上限 1023 是否够**：评估目标实例规模，若超 1024 需调整 snowflake 位分配（减 worker 位加 sequence 位或引入 datacenter 位）。
6. **`gateway.push` 广播量基线**：扩前采集广播/精确路由比例，作为 Phase 3 优化依据。
