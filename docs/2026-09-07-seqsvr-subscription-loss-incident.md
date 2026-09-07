# 2026-09-07 seqsvr 订阅丢失事故分析与修复方案

- 日期:2026-09-07
- 类型:线上事故分析 + 加固设计
- 影响面:所有依赖 `seqsvr.alloc.*` 发号的服务(消息发送 C2C/C2G 等)不可用约 3 天(09-04 晚间 ~ 09-07 17:37)
- 状态:已止血(两层:restart alloc 容器 + restart logic 容器),加固待实施

## 0. 事故分层总览

排查中发现事故实为**三层问题叠加**,逐层止血后才逐层暴露:

| 层 | 问题 | 表象 | 止血 |
|---|---|---|---|
| L1 | CM 订阅表丢失 alloc 条目(§3 根因一) | `No handlers for address seqsvr.alloc.*` | restart seqsvr-alloc-1/2 |
| L2 | Mediate 路由版本号倒退 + 客户端严格大于才采纳(§3 根因二) | `route outdated after retry: id X not in an active section` | restart pomelo(logic)清路由缓存 |
| L3 | alloc store 租约 5s 窗口频繁跳闸(35min 内 9 次),section 周期性掉激活 | 上述两层修复后仍偶发发送失败窗口 | 待加固(§5.4) |

## 1. 事故现象

2026-09-07 17:13 起,logic-server 在消息发送链路持续报错(24h 内 16 次):

```
17:13:59.419 DEBUG c.g.m.p.s.client.SeqClientService - node-scoped request failed,
    falling back to shared address: id=98946276, cause=No handlers for address seqsvr.alloc.alloc-1.fetchNext
17:13:59.421 ERROR c.g.moxib.pomelo.logic.LogicVerticle - Service 处理失败: No handlers for address seqsvr.alloc.fetchNext
```

特征:

- node-scoped 地址(`seqsvr.alloc.alloc-1.fetchNext`)与兜底地址(`seqsvr.alloc.fetchNext`)**双双 No handlers**;
- 客户端按 5s/10s/20s 重试 3 次后放弃,消息发送失败;
- 前端表现为消息长期停留在"发送中"。

## 2. 排查证据链

| # | 证据 | 结论 |
|---|---|---|
| 1 | `docker ps`:`seqsvr-alloc-1/2` Up 3 days,进程存活 | 排除"服务未启动" |
| 2 | alloc 启动日志(09-04 13:41):`registerConsumers()` 无条件注册兜底+节点地址 | 本地 consumer **存在** |
| 3 | Redis CM 订阅表 `pomelo:__vertx:subs`(hash):`seqsvr.store.*` 16 条、`seqsvr.mediate.*` 4 条、`logic.*`、`gateway.push*` 均在,**`seqsvr.alloc.*` 为 0 条** | 集群视图里 alloc 订阅**缺失** |
| 4 | `TTL pomelo:__vertx:subs` = -1 | 条目不会自动过期,属"写入失败/丢失"而非"过期" |
| 5 | alloc 启动日志(09-04 13:41:45-56):`ClusteredEventBus - Connecting to server <uuid> failed` 持续刷屏;`AllocManager - lease expired: no successful store read for 5000ms, stop serving` | 启动窗口正值**全集群启动风暴**,跨节点 mesh 与 store 读写均异常 |
| 6 | `docker restart seqsvr-alloc-1 seqsvr-alloc-2` 后 12s,`__vertx:subs` 出现全部 6 条 `seqsvr.alloc.*`;两个节点 `registered with Mediate (routerVersion=3/4)`,且本次启动无 mesh 报错 | **假设闭环**:订阅缺失即根因,重注册即恢复 |

## 3. 根因

**L1 直接原因**:Redis Cluster Manager 的订阅注册表(`pomelo:__vertx:subs`)中没有任何 `seqsvr.alloc.*` 条目。logic 按 Vert.x 集群事件总线语义解析地址时发现无订阅者,请求在发送前即失败(`No handlers for address`),与 alloc 进程是否存活无关。

**L1 深层原因**:alloc 两节点在 09-04 与全集群同时启动,恰逢启动风暴(mesh 连接失败、store 租约读写失败)。其消费者订阅的 CM 写入发生在该异常窗口内,写入失败/丢失后**没有任何重试与自愈路径**——Vert.x 只在 `eventBus.consumer()` 注册时写一次订阅,此后不再校验。alloc JVM 由此进入"僵尸可见性"状态:本地有 consumer、集群不可见,持续 3 天,直到首次消息发送触发 seq 分配才暴露。

### 3.1 L2 根因:Mediate 路由版本号倒退 × 客户端严格大于更新规则

alloc 容器重启止血后,错误变为 `route outdated after retry: id 98946276 not in an active section (section 989) of node alloc-1`。排查确认第二层问题:

1. **Mediate 路由版本号非单调**。09-04 集群启动时 alloc 注册产生 routerVersion=5/6(logic 客户端于 09-05 采纳 `version=6`);09-07 alloc 重启重新注册后,Mediate 产出的版本回落到 v1~v4。版本计数器随成员变化重建而非全局单调递增。
2. **客户端只认严格更大版本**。`SeqClientService.updateRouteFrom()`(`pomelo-seqsvr-client/.../SeqClientService.java:185`)条件为 `newRouter.getVersion() > routeVersion`——服务端返回的 v1~v4 全部被客户端(v6)拒绝,路由缓存**永久冻结在过期状态**。
3. **死循环路径**:`resolveResponse()` 收 ROUTE_OUTDATED → 采纳路由失败(版本不增)→ 用**同一份过期路由**重试 → 再次命中 alloc-1 → 再次 ROUTE_OUTDATED → `attempt >= MAX_RETRY` 且版本未变 → 抛 "route outdated after retry"。旧路由下 section 989 归 alloc-1,重启后新路由把它划给了 alloc-2,客户端永远到不了真正的 owner。

### 3.2 L3 根因:store 租约 5s 窗口跳闸

alloc 重启后 mesh 干净(0 次 Connecting 失败),但 35 分钟内出现 **9 次** `lease expired: no successful store read for 5000ms, stop serving`(17:37/17:54/17:55/18:11/18:20…),每次 1~3s 恢复。每次跳闸窗口内 alloc 的 section 掉激活态,该窗口内的发号请求即使路由正确也会收到 ROUTE_OUTDATED。store/mediate 容器同期无错误日志,怀疑与宿主机 Docker Desktop 资源停顿或 checkLeaseMs=5000 窗口过紧有关,需进一步定位(见 §5.4)。

**放大因素**(L1/L2 共同):

1. logic-server(Up 2 days)晚于 alloc 一天启动,启动即持有"无 alloc"的集群视图,从未见过正确状态;
2. `SeqClientService` 的三级降级(node 地址 → 兜底地址 → 重试)作用于**同一个坏掉的视图**,降级无效;
3. 无任何监控/日志能提前暴露"订阅缺失/路由版本冻结"——CM 写入失败、版本拒绝采纳都是静默的。

## 4. 具体问题清单

| # | 问题 | 位置 | 等级 |
|---|---|---|---|
| P1 | CM 订阅写入失败无重试、无对账、无自愈;一次写入失败即永久不可见 | `io.github.shadowbrook:RedisClusterManager`(库层) | Critical |
| P2 | 应用层(Verticle)对"我注册的地址集群是否可见"零校验;`registerConsumers()` 发后不管 | `SeqAllocVerticle.registerConsumers()` 及所有 Verticle 同模式 | Critical |
| P3 | 拓扑变化(节点加入/离开)时不重推自身订阅;启动风暴期间拓扑剧烈抖动,丢写概率被放大 | RedisClusterManager 库层 | Important |
| P4 | CM 写入失败静默——启动日志里没有任何"订阅注册失败"字样,排查只能靠 Redis 侧人肉比对 | RedisClusterManager / Vert.x 集成层 | Important |
| P5 | alloc 的 `AllocState`(WAIT_ROUTE_TABLE / STOP_SERVING 等)不对外暴露,无法被探针/监控捕捉"在册但不服务"状态 | `SeqAllocVerticle` / `AllocManager` | Important |
| P6 | 全部服务 `restart: unless-stopped` 同时启动,无启动时序错峰,风暴为必然事件 | `docker-compose.yml` | Minor |
| **P7** | **Mediate 路由版本号非单调**:成员变化重建路由时版本回落(v6→v1~v4),破坏版本单调语义 | `pomelo-seqsvr-mediate` 路由版本分配 | **Critical** |
| **P8** | **客户端路由更新规则无逃生通道**:`newRouter.getVersion() > routeVersion` 之外,对"重复 ROUTE_OUTDATED + 版本未增"没有强制采纳/清缓存/向 Mediate 拉取的兜底,路由缓存可永久冻结 | `SeqClientService.updateRouteFrom()/resolveResponse()` | **Critical** |
| **P9** | **store 租约窗口过紧**:checkLeaseMs=5000ms,任何 5s 读停顿(宿主机停顿/GC/CM 抖动)即 stop serving,section 掉激活直接对客户端暴露为 ROUTE_OUTDATED,无宽限与本地缓冲 | `SeqAllocConfig` / `AllocManager` | Important |

## 5. 修复方案

> **实施状态（2026-09-07，分支 `refactor/remove-json-codec`，TDD 全程）**：
> - P7 ✅ `f0535d3` — 空成员分支版本改为 `router.getVersion()+1`，不再归零。
>   设计取舍：未采用 Redis INCR 持久化计数器——Mediate 单实例下"内存自增 + Store 持久化基线（init 加载）"已保证单调；
   极端崩溃窗口（saveRouteTable 失败后进程崩溃）可能复用版本号一次，由 P8 客户端强制采纳兜底。
> - P8 ✅ `12b741d` — `SeqClientService`：连续 2 次 ROUTE_OUTDATED 强制采纳嵌入路由；连续 3 次向 Mediate
   `getRouter` 全量拉取；`retryAfterMs` 延迟重试；单次发号尝试上限 4 次；`updateRouteFrom` 返回是否采纳。
> - P9 ✅ `fa48fc6` — `LEASE_TIMEOUT_MS` 5000→15000（约 3× 同步周期）；新增 `PENDING_ACTIVATE_DELAY_MS=5000`
   解耦 pending 激活节奏；`ROUTE_OUTDATED` 回复统一携带 `retryAfterMs=2000`。
> - 遗留：RedisClusterManager 订阅自愈（P1~P4，库层）与 /health/alloc 探针（P5）待后续批次；
   仓库既有环境性失败（logic `RedisIdGeneratorTest`、gateway `TcpGatewayVerticleTest`，与本次改动无关，已在
   改动前基线复跑确认）需另行排查。

### 5.1 库层:RedisClusterManager 订阅自愈(治本,P1/P3/P4)

`io.github.shadowbrook:RedisClusterManager` 增加:

1. **订阅写入重试**:`addSubscription`(consumer 注册写 HSET)失败时指数退避重试(200ms→1s→5s,上限持续),失败必须输出 ERROR 级日志(修 P4),直至成功;
2. **周期对账(核心)**:CM 内存中维护"本节点应注册的订阅集合",每 30s 与 Redis 中 `HGET <subsKey> <address>` 比对(校验值里含本 nodeId);缺失即重写,并按 Vert.x SPI 语义触发相应回调。对账发生写冲突无副作用(HSET 幂等);
3. **拓扑变化重推**:监听节点加入/离开事件时,全量重推本节点订阅(防御性,修 P3)。

### 5.2 应用层:SeqAllocVerticle 自检兜底(止血升级,P2/P5)

1. **订阅自检定时器**:`SeqAllocVerticle.start()` 内 `registerConsumers()` 后,增加 30s 周期任务:对自身地址清单(`ALLOC_FETCH_NEXT`、`ALLOC_GET_CURRENT`、node-scoped×2)经 CM/Redis 校验订阅存在性;缺失时先 ERROR 日志,再对缺失地址重新执行 `consumer.unregister()+eventBus.consumer()` 强制重注册;
2. **服务状态探针**:`ApiVerticle`(或独立 HTTP health port)暴露 `GET /health/alloc`:`{state, serving, nodeId, routerVersion, subscriptionOk}`;`serving=false` 或 `subscriptionOk=false` 返回 503,供 compose healthcheck / K8s probe / 告警使用(修 P5);
3. 该模式应泛化为公共设施(`AbstractClusteredVerticle` 或 Verticle 基类工具),所有暴露 EventBus 地址的服务(gateway/logic/seqsvr-*)统一接入,避免下次是 store 或 logic 僵尸(修 P2 的面)。

### 5.3 部署层:启动风暴治理(P6)

- compose 为 alloc 增加 `depends_on: seqsvr-mediate(healthy)` 与 store healthy 条件;或接受风暴但以 5.1/5.2 兜底(推荐后者——分布式系统不能靠启动顺序保正确性)。

### 5.4 路由版本与租约加固(P7/P8/P9)

1. **Mediate 版本全局单调(治本,P7)**:路由版本改为持久化原子计数器(Redis `INCR`,与 store/CM 同基础设施),成员全量重建、部分变更、节点增删一律取 `INCR` 新版本;任何情况下不允许产生 ≤ 历史已发版本的号。
2. **客户端逃生通道(P8)**:`SeqClientService` 增加两条规则——
   - 同一 id 连续 2 次 ROUTE_OUTDATED 且嵌入路由版本 ≤ 本地版本 → **强制采纳嵌入路由**(信任服务端现实),重路由重试;
   - 连续 3 次 → 主动向 Mediate `getRouter` 全量拉取并重置缓存(仍失败则上抛)。
   附带:每次强制采纳/重置输出 WARN 日志,便于发现版本倒退。
3. **租约宽限与滞后判定(P9)**:
   - `checkLeaseMs` 与 `leaseMs` 解耦:检查窗口放宽到 lease 的 2~3 倍(如 lease 5s、check 15s),或改为"连续 N 次检查失败才 stop serving";
   - stop serving 期间收到本节点 section 的请求时,回复中携带 `retryAfterMs`(建议 2s),客户端据此延迟重试而非立即耗尽重试次数;
   - 排查宿主停顿:Docker Desktop 资源曲线 vs 9 次跳闸时间点(17:37/17:54/17:55/18:11/18:20…)对照,确认外因占比。

### 5.5 验证清单

| 用例 | 操作 | 预期 |
|---|---|---|
| 启动风暴演练 | `docker compose down && docker compose up -d`(全并发) | 各服务启动后 60s 内 `__vertx:subs` 中自身地址齐备 |
| 订阅人为丢失 | 手工 `HDEL` alloc 的 6 条订阅 | ≤30s(一个对账周期)自动补写;期间 ERROR 日志可见 |
| 进程僵死 | `docker kill seqsvr-alloc-1` | 节点离开集群,router 收敛;重启后按 5.2 自检恢复 |
| **版本倒退注入** | 重启 alloc 触发重新注册,观察 Mediate 产出版本 | 版本严格递增(≥历史最大);客户端 "Route table updated" 正常采纳 |
| **路由过期自愈** | 客户端缓存注入伪造旧路由(或回滚 P8 前版本复现) | 连续 ROUTE_OUTDATED 后强制采纳/拉取 Mediate,发号成功 |
| 端到端 | 前端发送文本消息 | 一次成功,`SeqClientService` 无 fallback 日志 |
| 可观测 | `GET /health/alloc` | state=serving、subscriptionOk=true |

## 6. 止血记录

| 时间 | 动作 | 结果 |
|---|---|---|
| 09-07 17:37 | `docker restart seqsvr-alloc-1 seqsvr-alloc-2`(L1 止血) | 12s 后订阅表恢复 6 条 `seqsvr.alloc.*`;两节点 registered with Mediate(routerVersion=3/4);错误由 No handlers 变为 route outdated |
| 09-07 17:48 | `docker restart pomelo`(L2 止血,清客户端路由缓存) | Logic-Server 正常启动;客户端 routeVersion 归零,待首次发号重新引导路由 |
| 待验证 | 前端发送文本消息 | 预期成功;若命中 L3 跳闸窗口可能偶发一次失败,重试即成功 |
