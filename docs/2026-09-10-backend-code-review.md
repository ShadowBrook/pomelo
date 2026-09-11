# Pomelo 后端代码 Review 报告

- **日期**：2026-09-10（2026-09-11 续修 P1-3~P1-8）
- **分支 / 基线**：`refactor/remove-json-codec` @ `dedc6a8`（Protobuf 单编解码迁移中）
- **修复进展**：P1 全部 9 项已修复（P0-1、P1-1~P1-9，见文末「六、修复记录」）
- **范围**：`pomelo-common`、`pomelo-gateway`、`pomelo-logic-server`、`pomelo-seqsvr`（client / mediate / server / store）、`pomelo-benchmark`，以及 `db/schema.sql`、`conf/`、`docker-compose.yml`、`deploy.sh`、各模块 `pom.xml` 与全部测试
- **方法**：逐文件通读 + 调用链交叉验证（写路径追到 SQL / 事件循环），每条结论均给出 `文件:行号` 证据；标题级问题已二次复核代码

---

## 一、总体结论

这是一套**设计意图清晰、核心难点基本做对**的 IM 后端：自定义二进制协议的双重长度校验、网关身份头服务端覆写、群消息幂等唯一键 + 单调已读游标、seqsvr 的租约/号段迁移/NRW 多副本与事故回归测试，都体现了对 IM 典型故障模式的理解。协议层与 seqsvr 的工程质量明显高于同阶段项目平均水平。

主要风险集中在三处**信任边界与并发模型的不对称**：

1. **服务层入参信任边界没有统一收口**——网关已经把身份规范化到 varHeader，但 `FriendService` 仍从请求体取操作者身份（P0），媒体签名器对任意对象 key 签发下载 URL（P1）。两者都是"网关防线已就绪、业务层没接"的低成本修复。
2. **网关写路径存在确定性数据竞争**——push 投递与连接响应写运行在不同事件循环上，却共享一个无锁的 `BoundedWriteQueue`（P1）。多核默认配置下随时可能触发丢推送/连接卡死。
3. **seqsvr 的租约不变量被最近的 P9 修复打破**——停服阈值（15s）已大于新号段激活延迟（8s），故障迁移存在双写窗口，可能导致同一用户 seq 重复（P1）。

另有 JWT 默认密钥入库、未认证连接无超时、workerId 租约续期无归属校验、pull limit 无上限等一批"单点即可触发"的缺陷。**优先级建议：先修 1 个 P0 + 5 个 P1（预计 1~2 天），再补 CI 与存储层测试**。

| 级别 | 数量 | 说明 |
|---|---|---|
| P0 | 1 | 可越权修改任意用户好友关系（已修复） |
| P1 | 9 | 越权读、数据竞争、seq 重复、静默丢消息、DoS 面（**全部已修复**） |
| P2 | 20+ | 边界条件、一致性缺口、配置与部署风险（P2-6 部分修复） |
| P3 | 15+ | 可维护性、注释漂移、测试与构建改进项 |

> 说明：P1-9（路由表版本号冲突导致号段双写）是在修复 P1-2 的验证过程中由两个失败的集成测试暴露出来的，
> 见下文 §P1-9 与 §六。

---

## 二、问题清单

### P0 — 必须立即修复

#### P0-1　FriendService 操作者身份取自客户端请求体，可伪造/破坏任意用户好友关系

- **文件**：`pomelo-logic-server/.../service/FriendService.java:154-157`（另有 `:99`、`:113`、`:128`）
- **问题**：四个好友操作的"本人 ID"全部来自请求体 `FriendOpRequest.userId()`，而不是网关注入的可信 varHeader。网关 `MessageDispatcher.normalizeSenderHeaders`（`MessageDispatcher.java:158-174`）只覆写 varHeader，body 原样透传，因此任意认证用户可指定 body 中的 `userId`。

```java
// FriendService.java:154-157
private Parsed parseFriendReq(ImMessage message) {
  FriendOpRequest req = decode(message, FriendOpRequest.class);
  return new Parsed(req.userId(), req.friendId());   // 客户端可控
}
```

同文件其余服务都是正确写法：`C2CService.extractSenderUserId` 优先取 header（`C2CService.java:181-184`），群相关服务统一用 `getUserIdFromHeaders`。

- **攻击路径**（任一认证用户可执行）：
  1. **无同意加好友**：攻击者 X 发 ADD `{userId: 受害者A, friendId: X}` 造出 pending 行；再发 ACCEPT `{userId: X, friendId: A}`，命中 `ACCEPT_FRIEND_SQL`（`user_id=A AND friend_id=X AND status=0`）置为好友，并反向插入 X→A，A 全程未同意。
  2. **任意删好友**：发 DELETE `{userId: A, friendId: B}` 即删除 A↔B 双向关系（`DELETE_FRIEND_SQL` 是双向 OR），并向 B 推送"被 A 删除"。
  3. **冒充他人发好友申请**：ADD `{userId: 受害者, friendId: 目标}`，目标收到"受害者请求加你"。
- **修复**：`Parsed.userId` 一律改为 `getUserIdFromHeaders(message)`，body 只提供 `friendId`；顺带对 SEARCH 也补 header 校验（当前完全无身份依赖，仅靠网关认证兜底）。

---

### P1 — 严重缺陷，特定条件触发故障

#### P1-1　网格写路径数据竞争：`BoundedWriteQueue` 被跨事件循环并发访问

- **文件**：`pomelo-gateway/.../handler/BoundedWriteQueue.java:29-31,38-57`；`MessageDispatcher.java:49-51,106`；`GatewayMain.java:34-35`
- **问题**：`pending`（`ArrayDeque`）、`drainHooked`、`closed` 是无同步普通字段，类注释假设"仅在事件循环线程访问，无需加锁"（`BoundedWriteQueue.java:18`）。但该假设不成立：
  - `MessageDispatcher` 在 **GatewayMain 的 context** 中构造，push consumer 注册在该 context 的事件循环 **L0**（`MessageDispatcher.java:49-51`）；push 投递走 `deliverToConnection → conn.write`（`:106`）→ 在 **L0** 上操作队列。
  - `TcpGatewayVerticle`/`WsGatewayVerticle` 是独立部署的 verticle，其 `NetServer`/`HttpServer` 的 socket 处理器运行在各自的事件循环 **L1**，业务响应 `connection.write(respBuf)`（`MessageDispatcher.java:144`）也在 **L1** 上操作同一个队列。

```java
// BoundedWriteQueue.java:42-56 —— 全程无同步
if (pending.isEmpty() && !stream.writeQueueFull()) { return stream.write(buffer); }
...
pending.add(buffer);
if (!drainHooked) { drainHooked = true; stream.drainHandler(v -> flush()); }
```

- **影响**：默认 event-loop 池为 2×CPU，多核下必然并发。后果是 `ArrayDeque` 结构损坏、写入静默丢失（推送收不到）、`drainHooked` 状态错乱后 pending 永不清空（连接只剩心跳、业务消息永久滞留），且**难复现**。这也是"偶发丢推送"的头号嫌疑。
- **修复**：在 `Connection.from()` 时捕获 socket 的 `Context`，`write()` 中若 `Vertx.currentContext() != ctx` 则 `ctx.runOnContext` 转投递（或在队列全部状态访问上加锁）。前者更符合 Vert.x 线程模型。

#### P1-2　seqsvr 租约不变量被破坏：停服阈值（15s）> 新号段激活延迟（8s），故障迁移存在双写窗口

- **文件**：`pomelo-seqsvr-server/.../alloc/AllocManager.java:60`、`:137`、`:236-249`
- **问题**：`LEASE_TIMEOUT_MS` 在 2026-09-07 事故 P9 修复中从 5s 放宽到 **15s**，注释明确写了"与 pending 激活延迟语义解耦"；而新号段激活延迟仍为 `pendingActivateDelayMs = 2 × syncLeaseMs = 8s`（`:137`）。两者原本构成的安全关系 `leaseTimeout < pendingActivateDelay` 被破坏：

| | P9 之前 | P9 之后 |
|---|---|---|
| 旧 owner 失去 Store 可见性后仍可发号 | ≤ 5s | **≤ 15s** |
| 新 owner 激活被迁移号段 | 8s | 8s |
| 重叠双写窗口 | 无（5 < 8，余量 3s） | **最长 7s** |

`AllocManager.java:69-74` 的注释只论证了"旧 owner 感知路由变更后继续发号的窗口 ≈ syncLeaseMs"，没有覆盖"旧 owner 与 Store 失联但进程仍活着"这一条——而后者正是 P9 事故的场景（宿主停顿 / 网络抖动 / msync 卡顿）。

- **影响**：双写窗口内旧节点与新节点都可对同一 section 发号。旧节点的 `curSeqs` 在内存（可能未落盘），新节点按 Store 值起步，两边可为**同一用户发出相同 seq**。下游 `im_message_c2c.seq` 是收件人同步水位，重复 seq 会导致消息在增量拉取中丢失/错乱。
- **修复**：恢复不变量，任选其一：① `pendingActivateDelayMs = leaseTimeoutMs + syncLeaseMs`（如 15+4=19s，代价是故障迁移后号段 19s 不可用）；② 停服阈值不接受放宽，改用"发号路径硬校验上次成功 Store 读时间"（每次 fetch 检查 `now - lastLeaseSuccess > leaseTimeout` 即拒绝，而不是等 1s 的检查定时器）；③ 在 Store 侧引入 fencing token（每次写带 owner 代数，被取代的写入直接拒绝）。**建议同时补一条断言测试**：`pendingActivateDelayMs > leaseTimeoutMs`。
  → **已按 ① 修复**，详见 §六；残留项：新节点「首次分配」仍即时激活（`applyRouter` 的 `WAIT_ROUTE_TABLE` 分支），该路径同样缺少迁移保护窗口，但加延迟会拖慢每次 AllocSvr 重启的首包时延，建议单独评估后再改。

#### P1-9　路由表版本号在并发注册下复用，导致同一 section 被两个 AllocSvr 同时服务

- **文件**：`pomelo-seqsvr-mediate/.../MediateManager.java:169-199`（`regenerateAndPersist` / `generateRouter`）
- **问题**：版本号取自 `router.getVersion() + 1`，而 `router` 只在**上一次持久化成功回调**里更新。两个 AllocSvr 几乎同时注册（滚动发布会稳定复现）时，第二次注册读到的仍是旧版本，于是生成出"**同版本号、不同内容**"的两张路由表：

```
DIAG mediate      v1 nodes=2 [node-1:6sections] [node-2:5sections]   ← 真正生效的路由表
DIAG node1.router v1 nodes=1 [node-1:11sections]                     ← 同版本号的旧表
DIAG node2.router v1 nodes=2 [node-1:6sections] [node-2:5sections]
DIAG fetch ... clientVersion=1 clientNodes=1                         ← 客户端也被钉在 1 节点视图
```

- **影响**：AllocManager 的租约同步用 `newRouter.getVersion() == router.getVersion()` 判定"无变化"直接跳过，于是**拿到错误同版本表的节点永远不会收敛**：它继续服务已不属于自己的号段（双写 → seq 重复），客户端则可能长期停在陈旧路由上。两个集成测试（`SeqSvrDrIntegrationTest`、`SeqSvrMediateIntegrationTest`）因此必然失败——不是 flake，是真实缺陷被测试正确抓到。
- **修复**：① 版本号改为独立单调计数器（`nextVersion`），与持久化结果解耦，且 `init()` 载入存量路由时取 `max`，保证重启后不复用；② 持久化串行化（`persistChain`），保证版本号顺序与落盘顺序一致，避免旧版本后写覆盖新版本。→ **已修复**，详见 §六。

#### P1-3　媒体读侧对任意对象 key 签发 GET URL（bucket 级越权读）

- **文件**：`pomelo-logic-server/.../service/MinioMediaUrlSigner.java:46-55`；`infrastructure/MinioObjectPresigner.java:70-82`
- **问题**：`signContent` 对消息 content JSON 中的 `key` 字段无任何归属校验即签发下载 URL，而 content 是**客户端可控自由文本**、原样入库（`C2GService.java:136`、`C2CService.java:109`）。`presignGet` 也不做前缀/归属校验。

```java
// MinioMediaUrlSigner.java:47-51
String key = obj.getString("key");
if (key == null || key.isBlank()) { return content; }
obj.put("url", presigner.presignGet(key));   // 任意 key 都被签名
```

- **攻击闭环**：任意认证用户发送一条 content 为 `{"key":"<目标对象key>"}` 的消息 → 拉取历史（`GroupPullService.java:111` / `PullService.java:105` 逐条签名）→ 响应里拿到有效的 presigned GET URL（默认 TTL **7 天**，`config.yaml:68`）→ 下载该对象。
- **影响**：可读取 bucket 内任意对象，包括其无权访问的会话媒体。对象 key 形如 `目录/userId/日期/snowflake.ext`，snowflake 单调递增，知道大致上传时间即可低成本枚举。
- **修复**（已实施，见 §六）：① 对象名改为 128 位随机十六进制（`ObjectKeys`），切断"知道大致上传时间即可枚举 key"这条攻击前提；② 新增 `MediaKeyGuard`，发送侧要求普通媒体的 key/thumb 必须是服务端签发形态且上传者就是发送者；③ 读侧只为形态合法的 key 签名。
  **残留**：转发/引用里的嵌套 key 仍只校验形态——转发项本质就是"引用原发送者的对象"，服务端无法区分合法转发与伪造；存量 Snowflake 命名的对象名也仍可枚举。彻底收口需要 `objectKey → 可见者集合` 的 share-grant 模型（列为后续项，未纳入本次）。

#### P1-4　未认证连接无任何超时，可用空闲 socket 耗尽资源

- **文件**：`TcpGatewayVerticle.java:58-63`；`WsGatewayVerticle.java:57-64`；`MessageDispatcher.java:220`；`SessionRegistry.java:153-158`
- **问题**：心跳定时器只在**认证成功之后**启动（`MessageDispatcher.java:220`），而 `NetServerOptions`/`HttpServerOptions` 只设置了 TLS，没有设置 `idleTimeout`/`readIdleTimeout`。Vert.x 默认 `DEFAULT_IDLE_TIMEOUT = 0`（不超时）。
- **影响**：连接后不发任何数据的 socket 永不回收（也不触发任何清理），慢速连接/半开连接攻击可直接耗尽 FD 与内存；反复 AUTH 失败的连接同理。
- **修复**（已实施）：双保险——① `NetServerOptions`/`HttpServerOptions` 设 `idleTimeout`（`gateway.idleTimeoutSeconds`，默认 120s，客户端心跳 30s）；② accept 时挂一次性认证截止定时器 `SessionRegistry.startAuthDeadline`（`gateway.auth.timeoutMs`，默认 30s），认证成功或断连即取消。

#### P1-5　JWT 使用仓库内置默认密钥，且无生产启动护栏

- **文件**：`pomelo-common/.../config/JwtTokenParser.java:33`；`conf/config.yaml:46`
- **问题**：代码兜底与提交的配置文件是同一个公开密钥：

```java
// JwtTokenParser.java:33
String secret = ConfigHolder.getString("jwt.secret", "pomelo-dev-secret-change-in-production");
```

```yaml
# conf/config.yaml:46
jwt:
  secret: "pomelo-dev-secret-change-in-production"
```

- **影响**：HS256 对称密钥即签发权。任何拿到仓库的人都能为任意 userId 签发合法 token，完成完整账户冒充；只要部署时没设置 `POMELO_JWT_SECRET` 就默认中招（`docker-compose.yml` 覆盖了 DB/Redis 却**没有**覆盖 jwt.secret / media.secretKey，且 Jib `extraDirectories` 会把 `conf/` 烤进镜像，如 `pomelo-gateway/pom.xml:102-109`）。
- **修复**（已实施）：`JwtTokenParser` 构造时检测"密钥为空或等于内置默认值"直接抛异常拒绝启动（唯一放行口是配置 `jwt.allowDefaultSecret: true`，仅本地开发用，并打 WARN）；`deploy.sh` 首次部署生成 `conf/jwt.env`（`openssl rand -hex 32`，已 gitignore），compose 用 `env_file` 给 logic 与 gateway 注入同一密钥。
  **残留**：镜像里烤进的 `conf/config.yaml` 仍带该开关的默认值 `false`，因此缺密钥的部署会硬失败；中期换 RS256/EdDSA 非对称方案。

#### P1-6　workerId 租约续期不校验归属 + 解析失败静默回退常量，可致 Snowflake ID 重复

- **文件**：`pomelo-logic-server/.../id/RedisWorkerIdProvider.java:74-82`；`id/WorkerIdResolver.java:29-31`
- **问题**：两处叠加：
  1. 续期用裸 `EXPIRE`，不校验 key 当前 value 是否仍属于自己。若进程与 Redis 失联超过租约 TTL（60s），SLOT 会被其他节点抢占，而本进程恢复后仍**继续使用同一 workerId**，其续期还会顺手延长新占用者的租约（双方都"合法"持有）。
  2. `WorkerIdResolver` 对任何 provider 失败都 `recover` 到下一个，最终静默回退 `snowflake.workerId` 配置默认值 **1**；多节点同时回退即得到相同 workerId。

```java
// WorkerIdResolver.java:29-31
return providers.get(index).resolve(vertx)
  .compose(id -> id != null ? ... : resolveFrom(..., index + 1))
  .recover(err -> resolveFrom(vertx, providers, index + 1));   // 失败静默降级
```

- **影响**：相同 `(timestamp, workerId, seq)` 可生成重复 Snowflake ID。该 ID 是用户主键（`ApiVerticle.register`）、消息主键（`C2CService.doSend` / `C2GService.doSend`），冲到 `im_message_c2c` 的 `(id, sender_id)` 主键会直接插入失败丢消息。
- **修复**（已实施）：① 续期改为 Lua CAS（`GET key == node` 才 `EXPIRE`），返回 -1 判定为失去租约，立刻停止续期并触发 fail-fast（`vertx.close()` 后 `System.exit(1)`）；② `WorkerIdResolver` 全部 provider 失败时不再回退常量 1，而是启动失败，只有显式 `snowflake.allowStaticWorkerId: true` 才允许用静态值（并打 WARN）。
  配套：`LogicMain`/`GatewayMain` 启动失败改为记录日志后退出（原来只打日志，进程残留为"半启动节点"，属 P2-6 的一部分）。

#### P1-7　拉取接口 limit 无上限，单请求可把全量历史读进内存

- **文件**：`PullService.java:50`；`GroupPullService.java:56`；`PgGroupRepository.java`（`PULL_MSG_BACKWARD_SQL` / `PULL_MSG_FORWARD_SQL` 的 `LIMIT $3`）
- **问题**：`int limit = req.limit() > 0 ? req.limit() : default;` 只做了下界判断，未设上界，直接透传 SQL `LIMIT`。
- **影响**：一条指令即可让服务端为单次响应分配巨型缓冲；群拉取还会对每条消息做 `presignGet`（HMAC + JSON 重编码，`GroupPullService.java:111`），在事件循环上放大 CPU 与内存压力，群历史越大越严重。
- **修复**（已实施）：`ServiceBase.resolvePullLimit` 统一收敛（请求值只补下界，上界截到配置 `message.maxPullLimit`，默认 200），`PullService` 与 `GroupPullService` 都走该入口。响应总字节上限未做（媒体 content 里只有签名 URL，单条量级可估，暂以条数上限兜底）。

#### P1-8　clientMsgId 用墙钟毫秒兜底 → 幂等键碰撞、消息静默丢失且返回"假成功"

- **文件**：`C2GService.java:68-72`、`:145-154`；同类问题见 `C2CService.java:51`、`:186-189`
- **问题**：proto `messageId == 0` 且 wire messageId 非数字时，幂等键兜底为 `System.currentTimeMillis()`：

```java
// C2GService.java:69-72
if (clientMsgId == 0) {
  try { clientMsgId = Long.parseLong(message.getMessageId()); }
  catch (NumberFormatException e) { clientMsgId = System.currentTimeMillis(); }
}
```

同一发送者在同一毫秒内的第二条消息将命中 `uq_group_client_msg` 唯一键 → `ON CONFLICT DO NOTHING` 吞掉 → 查回**第一条**消息按"重试幂等"返回成功（`:147-153`）。若 wire messageId 恰为 `"0"`，clientMsgId 保持 0，去重查询因 `clientMsgId <= 0` 直接返回 null（`PgGroupRepository.java:263-265`），响应携带**从未持久化**的 id 与 seq。

- **影响**：消息丢失但客户端收到成功确认（不会重试）；seq 污染客户端同步水位。**这不是理论路径**——仓库自带压测客户端就用非数字 messageId（`ImClient` 的 `"bench-" + UUID`），兜底分支真实可达。
- **修复**（已实施）：`ServiceBase.parseClientMsgId` 只接受"body messageId > 0"或"wire messageId 为纯数字且 > 0"，两者都没有时用 `SnowflakeIdGenerator.nextId()` 兜底——唯一且单调，不会像墙钟毫秒那样在同一毫秒内碰撞。实测兜底值落在 1e18 量级（Snowflake），与墙钟毫秒（1.7e12）明显区分。

---

### P2 — 一般缺陷 / 边界条件 / 可维护性

**网关与协议层**

| # | 问题 | 位置 | 说明 |
|---|---|---|---|
| P2-1 | WS 帧上限沿用 Vert.x 默认（64KB/帧、256KB/消息） | `WsGatewayVerticle.java:57-64` | 协议允许 2MB 帧/1MB body，TCP 能过、WS 被 Vert.x 直接断连（1009），行为分裂且表现为"连接莫名被关" |
| P2-2 | 跨节点顶号不生效 | `SessionRegistry.java:39-52` | `register` 只替换**本节点**会话；用户在 B 节点重登，A 节点旧连接仍保持完整认证身份（可继续发消息），全仓无跨节点踢下线机制 |
| P2-3 | `dispatcher.stop()` 绑错生命周期 | `MessageDispatcher.java:60-68`、`TcpGatewayVerticle.java:72-79` | 共享组件被两个 verticle 的 stop() 各调一次；单独 undeploy TCP 会注销**全部**在线用户路由（含 WS）。且 unregister 是 fire-and-forget |
| P2-4 | EventBus 回复解析异常被吞 | `MessageDispatcher.java:139-145` | `onSuccess` 内 `readFromWire` 抛异常时 Future 无人观察，客户端既无响应也无日志 |
| P2-5 | 集群配置两条加载路径不一致，且两份 config.yaml 已实质漂移 | `ClusterHelper.java:124-140` vs `ConfigHolder.java:52-55` | ClusterHelper 从 **classpath** 读 `conf/config.yaml`，ConfigHolder 从**文件路径**读。仓库里存在两份同名配置：`conf/config.yaml`（文件态：`redis://redis:6379`、连接池 64/5000、db host `postgres`、**无 keyNamespace**）与 `pomelo-common/src/main/resources/conf/config.yaml`（classpath 资源：`redis://localhost:6379`、连接池 4/8、db host `localhost`、`keyNamespace: pomelo`）——CM 的 namespace 与 endpoint 来自资源副本、业务配置来自文件副本。容器内之所以能工作，仅因为 compose 给每个服务都注入了 `POMELO_REDIS_CM_ENDPOINT`；一旦缺这个环境变量，CM 会按资源副本连 `localhost:6379` 而静默组不成集群 |
| P2-6 | 无 shutdown hook；启动失败仅打日志 | `GatewayMain.java:44-49` | SIGTERM 无人调 `vertx.close()`，节点下线清理（P2-3 的路由注销）实际从不执行；启动失败进程残留为"半启动节点" |
| P2-7 | HTTP API 无鉴权 | `ApiVerticle.java:94-96, 181-237` | `/api/user/:userId/profile`、`/api/friends/:userId`、`/pending` 无 token 校验，任意人可查任意用户资料与好友列表（历史约束中此项被排除在修复范围外，此处仅登记风险） |
| P2-8 | 登录无频控/锁定 | `ApiVerticle.java:149-178` | 密码可无限次暴力尝试 |

**业务服务层**

| # | 问题 | 位置 | 说明 |
|---|---|---|---|
| P2-9 | 建群两步写非事务 | `GroupManagementService.java:109-110` | `createGroup` 与 `addMember(OWNER)` 无事务；第二步失败留下**任何人不可见**的孤儿群（群列表 JOIN 成员表） |
| P2-10 | `maxMembers` 从不校验 | `GroupManagementService.java:102-107,143-164`；`PgGroupRepository.java:52-55` | 建群写死 200 但邀请链路无容量检查，群成员可无限增长，放大推送扇出与 DB 压力 |
| P2-11 | 群成员缓存 5s 无主动失效 | `GroupMemberContextCache.java:40-45`；`C2GService.java:94-106` | kick/mute/invite 后最长 5s 内：被踢者仍能发消息（且消息真实入库并扇出）、新成员被误拒。建议变更路径显式 `invalidate` |
| P2-12 | 成员变更通知无离线补偿 | `GroupManagementService.java:179-206`；`MessageDispatcher.java:94-97` | 纯 push，目标离线即永久错过 KICKED/INVITED，客户端只能靠重登刷新群列表发现 |
| P2-13 | 上传大小仅信客户端声明，无配额 | `UploadService.java:36,61-70`；`MinioObjectPresigner.java:53-67` | presigned PUT 不限制实际对象大小，声明 1KB 可实传 10GB；content-type 不入签名；无每用户配额/频控 |
| P2-14 | seq 先取后写，前向拉取可能出现空洞 | `C2CService.java:92-109`；`C2GService.java:132-137` | 并发写时后取号的先落库；客户端若以"收到的最大 seq"作为水位，`seq > sinceSeq` 会永久跳过尚未落库的小 seq 消息。建议客户端水位取"最小连续未读"或服务端补洞 |
| P2-15 | 好友搜索/错误语义粗糙 | `FriendService.java:25-28,79-81,105,124-133` | keyword 未转义 `%`/`_` 且前导通配触发全表扫；`recover` 把 DB 故障等一切异常映射成 CONFLICT"已申请"；DELETE 不看 rowCount 一律回"已删除" |

**基础设施层**

| # | 问题 | 位置 | 说明 |
|---|---|---|---|
| P2-16 | 分区键与查询维度不一致 | `db/schema.sql:75-99` | 表按 `sender_id` HASH 分区，但热查询按 `recipient_id`（离线拉取）与 `conversation_id`（会话历史）过滤 → 无分区裁剪，4 分区尚可，扩容后线性劣化 |
| P2-17 | `RedisIdGenerator` 已成死代码 | `id/RedisIdGenerator.java`（357 行） | seq 已迁至 seqsvr，主代码仅测试引用；`IdGenerator`/`nanoid`/`idGenerator` 配置项同样失去用途 |
| P2-18 | NRW 缺少 `W + R > M` 校验 | `StoreClients.java:26-33`；`ReplicatedStoreClient.java:36-44` | 语义正确性依赖 `W+R>M`（当前 2+2>3 成立），但配置层无校验、无文档强制；误配 M=3/W=2/R=1 会出现陈旧读 → seq 回退 |
| P2-19 | `saveMaxSeq` 在事件循环上做 mmap 落盘 | `StoreManager.java:106-126` | 每次 section 提升都 `MappedByteBuffer.force()`（同步 msync，可能毫秒级卡顿）。建议改批量/异步落盘或延迟 force |
| P2-20 | 黑名单检查 Redis 故障时 fail-open | `TokenService.java:98-101` | Redis 不可用时 `complete(false)`，已登出/撤销的 token 仍可用 |
| P2-21 | Snowflake 时钟回拨 > 5s 直接抛异常 | `SnowflakeIdGenerator.java:106-110` | 回拨 >5s 时 `nextId()` 抛 `IllegalStateException`，用户注册与消息发送直接失败；建议降级策略（等待/借用备用 workerId） |
| P2-22 | schema 注释与实现漂移 | `db/schema.sql:58,124` | `seq` 仍写"RedisIdGenerator 全局ID""群消息暂未接入"，实际已是 seqsvr 收件人/群同步版本号 |

---

### P3 — 改进建议

**代码与协议**
- `ImMessage` 协议严格性：`readFromWire` 接受任意 `codecId`（迁移后应只允许 0）、version 只拒 `>1`（未定义的 0 放行）、局部变量 `magic` 读后未回填到字段、`ImMessage(boolean dummy)` 语义不明（`ImMessage.java:104-119,213-220`）
- `PushCodec.decode` 无边界校验（畸形 Buffer 抛 IOOBE 后该条 push 被丢弃且无计数）；`PushEnvelope.correlationMsgId` 从未赋值也未参与编解码，属死字段（`PushCodec.java:35-49`、`PushEnvelope.java:16,35-36`）
- 异常处理 NPE：`throwable.getMessage().contains(...)` 在 message 为 null 时抛 NPE（`TcpGatewayVerticle.java:127`、`WsGatewayVerticle.java:129`）
- `cmdToAddress` 用硬编码区间 `0x0070..0x009B` 路由群命令（`MessageDispatcher.java:259`）；新增命令越界即静默误路由，建议改为显式逐 cmd 映射或由枚举生成
- `deliverToConnection` 在 `conn == null` 时静默 return，路由陈旧导致的丢推无日志无指标（`MessageDispatcher.java:94-97`）
- 心跳定时器在每条消息上 cancel + set（`SessionRegistry.java:153-158`），高频用户定时器 churn 明显，可改为 deadline + 单扫描定时器
- `ConfigHolder` 环境变量覆盖会把纯数字字符串强转数值（`ConfigHolder.java:113-123`），`POMELO_DATABASE_PASSWORD=123456` 这类值会让 `getString` 抛 `ClassCastException`
- JSON codec 移除残留：`MessageCodec.java:25`、`CodecRegistry.java:46-53`（codecId 参数被忽略）、`ProtobufCodec.java:24` 的 javadoc 仍描述 JsonFormat 路径
- `Cmd` 与 proto 双份维护：前端 SDK 的 `Cmd` 枚举手写（已出现漏加 `GROUP_KICK_RESP` 导致请求超时的事故）

**测试与构建**
- **存储层零测试**：`PgMessageRepository`(206 行) / `PgGroupRepository`(346 行) 无任何测试，全仓无 Postgres Testcontainers；`GroupManagementService`(453 行) 的角色权限矩阵（踢人/邀请的 owner/admin/member 层级）无测试；JWT 认证链路无测试
- **无 CI**：仓库没有 `.github/workflows`，27 个测试类（含 Testcontainers 集成测试）只能本地手跑；seqsvr 那套带事故编号的回归测试尤其值得 CI 化
- 可能空转通过的测试：`MessageDispatcherTest.authenticatedHeaderSpoofingIsNormalized` 用无条件定时器完成（`:155`），转发链路回归时测试仍会通过
- sleep 式收敛等待：`SeqSvrDrIntegrationTest:117`(2s)、`SeqSvrMediateIntegrationTest:134`(1.5s)、`TcpGatewayVerticleTest:55`(300ms) 等，慢 CI 上 flake 风险
- Docker 硬依赖无守卫：`RedisIdGeneratorTest`/`MinioObjectPresignerTest` 无 `@EnabledIfDockerAvailable`；`minio/minio:latest` 未固定版本
- 版本错配：`protoc 3.25.5`（`pomelo-common/pom.xml:102`）配 `protobuf-java 4.31.1`（根 `pom.xml:30`）；jackson-databind 硬编码 2.20.1 配 jackson-core 2.21.1（根 `pom.xml:31,95`）；Testcontainers 根 BOM 1.19.3 被模块 1.21.4 覆盖；`protobuf-maven-plugin 0.6.1` 已停维护
- 依赖陈旧：JUnit 5.9.1、maven-shade 3.2.4
- `pomelo-gateway/pom.xml:63-67` 以 test scope 依赖 `pomelo-logic-server`，但无任何 gateway 测试实际引用，属无谓耦合
- 文档漂移：AGENTS.md 写 Java 17，实际编译 release=21、镜像 temurin:21-jre

**部署**
- 镜像**仅构建 `linux/arm64`**（各模块 Jib `<platforms>`，如 `pomelo-gateway/pom.xml:83-88`），无法在 x86_64 服务器/CI 运行，deploy.sh 与 compose 均未提示
- `postgres:latest` 未固定（`docker-compose.yml:82`），且 `pgdata` 挂载路径只适配 PG18+ 镜像布局
- 应用服务（pomelo/gateway/seqsvr-*）无 healthcheck，而 `/api/health` 与 seqsvr-alloc `/health` 都已就绪可用；`depends_on` 多为 `service_started`
- 无内存/CPU 限制、无日志驱动配置（json-file 默认无上限）
- 开发凭据入库：`conf/config.yaml:31,65-66` 的 DB/MinIO 口令、`docker-compose.yml` 的 `pomelo123`/`pomelo-admin-password`；Redis 无 `requirepass`、5432/6379/9002/9003 全量映射到宿主（建议开发库端口改绑 127.0.0.1）

---

## 三、分模块评价

**pomelo-common（协议 / 编解码 / 路由 / 配置）**
协议解析的防御深度是这套代码最扎实的部分：长度前缀在 RecordParser 侧与 `readFromWire` 内**双重校验**，对 messageId、变长头、body 均有"非负 + 类型上限 + 不超剩余字节"检查，配合 header 数量上限，恶意 2MB 声明无法造成越界读或大额预分配。会话路由的清理语义正确（`unregisterByConnection` 校验连接归属、`removeIfPresent` 条件删除），顶号/重连窗口不会误删新路由；节点存活复用 cluster manager 的 nodeInfo 目录（P9/option-A 重构）方向正确。主要问题是 JWT 默认密钥（P1-5）与 Key 配置双路径（P2-5）。

**pomelo-gateway**
分层清晰、身份可信链设计到位（未认证连接仅放行 PING/AUTH，认证后服务端覆写 userId/userName/nickname），有界写队列的问题意识正确。但并发模型有一处硬伤（P1-1），加上未认证连接无超时（P1-4）、跨节点顶号缺失（P2-2）、stop() 生命周期绑错（P2-3）。

**pomelo-logic-server**
业务骨架质量高：C2C/群消息的幂等唯一键 + 原子 `ON CONFLICT DO NOTHING` + 冲突查回、`GREATEST` 单调已读游标、双向会话 `conversation_id`、批量用户信息 IN 查询、失败链路统一由 `LogicVerticle.dispatch` 回错误（无客户端悬挂），都是 IM 场景的正确做法。问题集中在"入参信任边界没收口"（P0-1、P1-3）与容量/一致性的边界（P1-7、P2-9~P2-14）。

**pomelo-seqsvr**
四个子服务（client/mediate/alloc/store）职责分明，号段迁移、pending 延迟激活、saveMaxSeq 失败升级停服、嵌入式路由表 + 版本倒退逃生，都是踩过坑之后的成熟设计；测试分层完整（状态机单测、`FlakyStoreAccessor` 故障注入、DR 演练、NRW 仲裁、带事故编号的回归）。当前最需要处理的是 P1-2 的租约不变量被打破，以及 P2-18 的 NRW 配置校验、P2-19 的 force() 落盘位置。

**工程实践**
`AGENTS.md` 的两条硬规范（代码体内禁用全限定名、禁止行尾注释）在 main + test 全量 grep 下**零违规**，执行得比大多数团队严格；手动探针/性能基准按 surefire 命名约定排除出常规构建、测试用临时目录并清理系统属性，细节老练。短板是流程未闭环：无 CI、存储层无测试、部署侧"开发便利泄漏进生产"（默认密钥进镜像、端口全暴露）。

---

## 四、建议的修复顺序

> 状态：第 1~9 项已全部完成（见 §六）；第 10 项起未动。

**第一批（1~2 天，安全与数据正确性，建议立即做）**
1. ~~P0-1 FriendService 身份改用可信头（改动极小，风险消除最大）~~ ✅
2. ~~P1-3 媒体 key 归属校验~~ ✅
3. ~~P1-5 JWT 默认密钥 fail-fast + compose 注入密钥~~ ✅
4. ~~P1-4 未认证连接 idle 超时~~ ✅
5. ~~P1-8 clientMsgId 兜底改为单调序列~~ ✅
6. ~~P1-7 拉取 limit 上限~~ ✅

**第二批（并发与迁移正确性，需要设计确认）**
7. ~~P1-1 `BoundedWriteQueue` 归属事件循环（或加锁）~~ ✅
8. ~~P1-2 恢复租约不变量（决定迁移可用性 vs 双写风险的取舍）+ 补断言测试~~ ✅
9. ~~P1-6 workerId 续期 CAS + 禁止静默回退~~ ✅
10. P2-2 跨节点顶号语义先定产品口径（单设备 / 多设备）再落地

**第三批（工程质量闭环）**
11. 引入 CI（`./mvnw test` 作为门禁，seqsvr 回归测试优先）
12. 补 Postgres Testcontainers + Pg 仓库测试 + 群权限矩阵测试
13. 修 P2-9/P2-10/P2-11（建群事务、成员上限、缓存失效）
14. 部署收口：镜像多架构、healthcheck、端口收敛、版本固定

**第四批（按迭代节奏）**
15. P2-12 离线成员变更补偿、P2-13 上传配额、P2-14 拉取空洞、P2-16 分区策略，以及 P3 清单

---

## 六、修复记录

### 6.1 第一批（2026-09-10：P0-1 / P1-1 / P1-2 / P1-9）

| 编号 | 状态 | 改动 |
|---|---|---|
| P0-1 | 已修复 | `FriendService.parseFriendReq` 操作者身份改取 gateway 注入的 varHeader；`process` 增加未认证拦截；`FriendOpRequest.userId` 标注为仅 wire 兼容 |
| P1-1 | 已修复 | `Connection.from()` 捕获连接所属 `Context`；`BoundedWriteQueue` 跨 context 写入经 `runOnContext` 转投递，队列状态固定单线程访问 |
| P1-2 | 已修复 | `pendingActivateDelayMs = leaseTimeoutMs + syncLeaseMs`（结构上保证不变量，不可被配置破坏）；更新两处 javadoc |
| P1-9 | 已修复 | `MediateManager` 版本号改为独立单调计数器 + 持久化串行链 |
| —（额外） | 已修复 | 测试侧资源引用问题：`TcpGatewayVerticleTest.connect()` 与 `SeqAllocVerticleTest` 的健康检查各自持有 `NetClient`/`HttpClient` 强引用 |
| —（额外） | 已修复 | `SeqSvrPerfBenchmark` 使用临时 `dataDir`，不再把 mmap 文件写进模块目录 |

### 6.2 第二批（2026-09-11：P1-3 ~ P1-8）

| 编号 | 状态 | 改动 |
|---|---|---|
| P1-3 | 已修复 | 新增 `ObjectKeys`：对象名改 128 位随机十六进制（原 Snowflake 可枚举），并提供形态校验/归属解析；`UploadService` 改用它（顺带去掉不再需要的 `SnowflakeIdGenerator` 依赖）；新增 `MediaKeyGuard` 在 `C2CService`/`C2GService` 落库前校验"普通媒体的 key/thumb 必须是服务端形态且归属发送者"；`MinioMediaUrlSigner` 读侧只为形态合法的 key 签名。**残留见 §P1-3** |
| P1-4 | 已修复 | 网关 accept 时挂认证截止定时器（`SessionRegistry.startAuthDeadline`，`gateway.auth.timeoutMs` 默认 30s，认证成功/断连即取消）；server options 另设 `idleTimeout`（`gateway.idleTimeoutSeconds` 默认 120s） |
| P1-5 | 已修复 | `JwtTokenParser` 拒绝以空/内置密钥启动（唯一放行口 `jwt.allowDefaultSecret: true`，仅本地开发并打 WARN）；`deploy.sh` 生成 `conf/jwt.env`（随机 32 字节，已 gitignore）；compose 对 logic 与 gateway `env_file` 注入同一密钥 |
| P1-6 | 已修复 | 租约续期改 Lua CAS（`GET == node` 才 `EXPIRE`），失租即停续期并 fail-fast（`vertx.close()` 后 `System.exit(1)`）；`WorkerIdResolver` 全部 provider 失败不再回退常量 1，改为启动失败，仅 `snowflake.allowStaticWorkerId: true` 放行静态值 |
| P1-7 | 已修复 | `ServiceBase.resolvePullLimit` 统一收敛上界（`message.maxPullLimit` 默认 200），`PullService`/`GroupPullService` 共用 |
| P1-8 | 已修复 | `ServiceBase.parseClientMsgId` 只接受正数幂等键，缺失时用 Snowflake 兜底；`C2CService`/`C2GService` 不再使用墙钟毫秒 |
| —（额外，属 P2-6 一部分） | 已修复 | `LogicMain`/`GatewayMain` 启动失败改为记录日志后关闭 Vertx 并 `exit(1)`，不再残留"半启动节点" |

**新增/调整的测试**

| 测试 | 覆盖 |
|---|---|
| `FriendServiceIdentityTest`（新，3 例） | body 伪造 userId 不影响落库身份；缺认证头被拒且不触库；伪造 ACCEPT 被识别为自操作 |
| `BoundedWriteQueueTest`（新，2 例） | 跨事件循环写入归一到连接 context；缓冲溢出判定慢消费者并断连 |
| `AllocManagerLeaseTest`（改，10 例） | 不变量 `pending > leaseTimeout`；激活边界（差 1ms 不激活）；延迟随停服阈值放大 |
| `MediateManagerTest`（改/新，8 例） | 并发注册不得复用版本号；落盘顺序与版本号一致 |
| `SessionRegistryAuthDeadlineTest`（新，3 例） | 超时未认证连接被关闭；认证成功即取消截止；认证前断连不再触发 |
| `JwtTokenParserSecretGuardTest`（新，5 例） | 缺密钥/内置密钥/空白密钥拒绝启动；独立密钥可用；显式放行时可用 |
| `PullLimitClampTest`（新，4 例） | 单聊与群拉取的超大 limit 都被截到 200、未设置时取默认值、合法值原样透传 |
| `ClientMsgIdFallbackTest`（新，5 例） | 非数字/为 0 的 wire messageId 兜底为 Snowflake 量级且互不重复；客户端数字幂等键原样保留 |
| `ObjectKeysTest`（新，3 例） | 生成 key 形态合法且 200 次不重复；兼容存量 Snowflake 命名；拒绝 URL/路径穿越等非法形态 |
| `MediaKeyGuardTest`（新，7 例） | 普通媒体只允许自己的对象；转发/引用可引用他人对象但 key 仍需形态合法；非媒体与非法 JSON 放行 |
| `MediaOwnershipWiringTest`（新，3 例） | C2C/C2G 引用他人对象的消息被拒且**不落库**；自己的对象正常发送 |
| `MinioMediaUrlSignerTest`（改，7 例） | 新增"非法 key 不签名"；存量 Snowflake key 仍可签 |
| `UploadServiceTest`（改，6 例） | 断言新 key 形态与"key 内上传者=认证用户" |
| `WorkerIdResolverTest`（改，6 例） | 全部 provider 失败时**启动失败**（不再回退常量）；显式放行后可用静态值 |
| `RedisWorkerIdProviderLeaseTest`（新，2 例） | 真实 Redis + 真实 Lua：租约被抢占后续期不再续并触发 fail-fast、抢占者 TTL 不被续；仍持有租约时正常续期 |

**验证方式与结果**

- 全量测试（`./mvnw test -fae`）结果：
  - `pomelo-common` 48 例、`pomelo-seqsvr-client` 7 例、`pomelo-seqsvr-store` 5 例、`pomelo-seqsvr-mediate` 8 例：全绿
  - `pomelo-seqsvr-server` 27 例全绿，重复 6 次均全绿（含此前必然失败的 `SeqSvrDrIntegrationTest`、`SeqSvrMediateIntegrationTest`）
  - `pomelo-gateway` 17 例全绿，重复 8 次均全绿（修复前基线约 10 次运行失败 4 次）；补 P1-4/P1-5 后为 20 例全绿
  - `pomelo-logic-server`：80 例中 73 例通过，7 例失败全部落在 `RedisIdGeneratorTest`（该 9 例在未改动基线上同样是 1 失败 6 错误）；排除这个已死代码的测试类后 71 例全绿
  - 注：`pomelo-gateway` 以 test scope 依赖 `pomelo-logic-server`，上一行失败会让网关模块被 reactor 跳过，因此网关需单独跑（已单独跑过，20 例全绿）
- **续修过程中被新测试抓到的回归**：`C2CServiceTest.imageNotifyContentIsSigned` 用的 key 是 `image/100/x.jpg`——服务端从不会签发这种形态，正是 P1-3 要挡的输入。已改为真实形态的 key（测试用假数据与生产校验对齐）。
- **测试 flake 的定位方法**（供后续参考）：先 `git stash` 在未改动基线上复现同一失败，再用临时探针验证机制，最后修复并做多次重复运行确认。
  两个 flake 的根因均为 Vert.x 5 的资源回收——被 GC 的 `NetClient`/`HttpClient` 会自动关闭其连接，
  而测试里这两个对象都是"随用随弃"的局部变量；探针实测：保留强引用时连接不被关闭，丢弃引用后连接被关闭。
- **已知且与本次改动无关的失败**（已在未改动基线上复现，失败签名完全一致，建议按 P2-17 清理）：
  - `RedisIdGeneratorTest`：9 例中 1 失败 6 错误（该生成器已是死代码，仅测试引用；表现为 ID 重复/回退与超时）

### 6.3 本次改动新增的配置项

| 配置 | 默认 | 用途 |
|---|---|---|
| `gateway.auth.timeoutMs` | 30000 | 未认证连接的认证截止 |
| `gateway.idleTimeoutSeconds` | 120 | 连接不活跃上限（客户端心跳 30s） |
| `message.maxPullLimit` | 200 | 单次拉取条数硬上限 |
| `jwt.allowDefaultSecret` | false | 仅本地开发允许用内置密钥（部署必须注入 `POMELO_JWT_SECRET`） |
| `snowflake.allowStaticWorkerId` | false | 仅单节点允许 workerId 回退到配置常量 |
| `snowflake.leaseRenewIntervalMs` | 30000 | workerId 租约续期间隔 |

### 6.4 尚未处理的后续项

- **P1-3 残留**：转发/引用中的嵌套 key 只校验形态，服务端无法区分"合法转发原消息"与"伪造引用他人对象"；存量 Snowflake 命名的对象名仍可枚举。彻底收口需 `objectKey → 可见者集合` 的 share-grant 模型（上传时登记 owner，投递成功时把可见者写进集合，发送时校验发送者是否在集合内）。
- **P1-2 残留**：新节点「首次分配」仍即时激活（`applyRouter` 的 `WAIT_ROUTE_TABLE` 分支），该路径同样缺少迁移保护窗口。
- 第三、四批（CI、Pg 层测试、P2-9~P2-14、部署收口）未动。
