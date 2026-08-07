# seqsvr 运维手册

> 最后更新：2026-08-04
> 覆盖版本：Jib 镜像 + docker compose 部署

---

## 一、架构速览

```
                    ┌──────────────┐
  业务方/客户端 ────▶│    Redis     │◀── 集群 EventBus（pub/sub，所有服务经 Redis 通信）
  (seqsvr client)   └──────────────┘
                         │
  ┌─────────┐  ┌─────────┐  ┌─────────┐
  │store-1  │  │store-2  │  │store-3  │  StoreSvr 副本（NRW：写 W=2 / 读 R=2）
  └────┬────┘  └────┬────┘  └────┬────┘
       └────────────┼────────────┘
               ┌────┴────┐
               │mediate  │  MediateSvr 仲裁（路由表生成 / 故障迁移）+ admin HTTP :10106
               └────┬────┘
         ┌──────────┴──────────┐
         │alloc-1              │alloc-2   AllocSvr 分配层（互为备机，均衡分号段）
         └─────────────────────┘

  ┌──────────┐   ┌──────────┐
  │ gateway  │   │ pomelo   │  业务层（同网络，集群 EventBus 互通）
  │ :9000    │   │ :8888    │
  │ :9001    │   │          │
  └──────────┘   └──────────┘
```

**数据流关键约束**：vertx-redis-clustermanager 的 EventBus 消息投递是节点直连容器 IP。客户端必须在同一 Docker 网络内，设置了 `POMELO_REDIS_CM_ENDPOINT=redis://redis:6379` + `VERTX_CLUSTER=true`，否则 EventBus 消息无法送达。

---

## 二、构建镜像

### 前置条件

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 21+ | `JAVA_HOME=/opt/homebrew/opt/openjdk`（macOS Homebrew） |
| Maven | 3.9+ | 项目自带 `./mvnw` wrapper |
| Docker | 29+ | Docker Desktop，已配代理 5780 |
| 代理 | `127.0.0.1:5780` | Jib 拉基础镜像用；Maven Central 直连 200，无需代理 |

### 构建步骤

Jib 的 CLI goal 不能带 `-am`（否则会在依赖模块上执行），所以分两步：先 `package` 产出 class 和依赖 jar，再 `jib:dockerBuild` 打镜像。

```bash
# 1. 编译全部模块（含依赖传递）
./mvnw -pl pomelo-seqsvr/pomelo-seqsvr-store \
       -pl pomelo-seqsvr/pomelo-seqsvr-server \
       -pl pomelo-seqsvr/pomelo-seqsvr-mediate \
       -pl pomelo-logic-server \
       -am package -DskipTests

# 2. 依次打 4 个镜像到本地 Docker daemon
for mod in pomelo-seqsvr/pomelo-seqsvr-store \
           pomelo-seqsvr/pomelo-seqsvr-mediate \
           pomelo-seqsvr/pomelo-seqsvr-server \
           pomelo-logic-server; do
  ./mvnw -pl "$mod" \
    com.google.cloud.tools:jib-maven-plugin:3.4.0:dockerBuild
done
```

构建耗时：首次 ~3 分钟/模块（拉基础镜像），增量 ~30 秒（依赖层缓存命中）。

### 版本说明

- Jib 插件版本固定为 `3.4.0`（已缓存于 `~/.m2`），不随 Central 自动升级
- 基础镜像 `eclipse-temurin:21-jre`（~210MB），产出镜像 ~150MB（应用层 ~15MB）
- 平台 `linux/arm64`；运行用户 `1000:1000`（非 root）

---

## 三、启动

### 全部启动

```bash
docker compose up -d
```

### 仅启动 seqsvr（不含主应用 pomelo）

```bash
docker compose up -d redis \
  seqsvr-store-1 seqsvr-store-2 seqsvr-store-3 \
  seqsvr-mediate seqsvr-alloc-1 seqsvr-alloc-2
```

### 启动顺序

Compose 已配 `depends_on`，自动按依赖顺序拉起：

```
redis (healthy)
  → postgres (healthy)
    → pomelo (logic-server)
  → store-1, store-2, store-3 (并行)
    → mediate (service_started from store-1)
      → alloc-1, alloc-2 (并行)
  → gateway (service_started from pomelo)
```

### 重启/更新单个服务

```bash
# 重新构建该模块镜像（如有代码变更），然后替换容器
./mvnw -pl pomelo-seqsvr/pomelo-seqsvr-server \
  com.google.cloud.tools:jib-maven-plugin:3.4.0:dockerBuild
docker compose up -d --no-deps seqsvr-alloc-1
```

---

## 四、验证

### 4.1 容器状态

```bash
docker compose ps
```

预期输出：

```
NAME              STATUS                    IMAGE
pomelo            Up N minutes              pomelo/pomelo
pomelo-gateway    Up N minutes              pomelo/gateway
pomelo-postgres   Up N minutes (healthy)    postgres:latest
pomelo-redis      Up N minutes (healthy)    redis:7-alpine
seqsvr-alloc-1    Up N minutes              pomelo/seqsvr-alloc
seqsvr-alloc-2    Up N minutes              pomelo/seqsvr-alloc
seqsvr-mediate    Up N minutes              pomelo/seqsvr-mediate
seqsvr-store-1    Up N minutes              pomelo/seqsvr-store
seqsvr-store-2    Up N minutes              pomelo/seqsvr-store
seqsvr-store-3    Up N minutes              pomelo/seqsvr-store
```

### 4.2 端口监听

```bash
docker compose port gateway 9000   # TCP Gateway
docker compose port gateway 9001   # WebSocket Gateway
docker compose port pomelo 8888    # HTTP API
```

### 4.3 Gateway 日志

```bash
docker compose logs gateway | grep -iE "start|listen|gateway"
```

预期看到：

```
TCP Gateway 已启动，监听端口：9000
WebSocket 服务器已启动，监听端口：9001
Gateway started: deploymentId=1, clustered=true
```

### 4.4 Logic-Server 日志

```bash
docker compose logs pomelo | grep -iE "start|listen|api"
```

预期看到：

```
ApiVerticle 已启动，端口: 8888
Logic-Server started: deploymentId=1, clustered=true
```

### 4.5 路由表（Mediate admin HTTP）

```bash
# 注意：若宿主 shell 设置了 http_proxy，需 --noproxy "*" 避免被代理拦截 localhost
curl --noproxy "*" -s http://localhost:10106/router | python3 -m json.tool
```

预期输出：

```json
{
  "version": 2,
  "nodeList": [
    {
      "nodeId": "alloc-1",
      "ip": "127.0.0.1",
      "port": 10100,
      "sectionRanges": [
        { "idBegin": 0, "size": 1073800000 }
      ]
    },
    {
      "nodeId": "alloc-2",
      "ip": "127.0.0.1",
      "port": 10100,
      "sectionRanges": [
        { "idBegin": 1073800000, "size": 1073700000 }
      ]
    }
  ]
}
```

**判定标准**：
- `version ≥ 1` — Mediate 已生成路由表
- `nodeList.length ≥ 1` — 至少一个 AllocSvr 注册成功
- 两个 AllocSvr 的 `sectionRanges` 覆盖全集 `[0, 2^31)` ≈ 21.47 亿

### 4.6 节点存活

```bash
curl --noproxy "*" -s http://localhost:10106/nodes | python3 -m json.tool
```

### 4.7 AllocSvr 注册日志

```bash
# 确认 alloc-1 已从 Mediate 拿到路由、号段已分配
docker compose logs seqsvr-alloc-1 | grep -iE "register|initialized|router"

# 预期看到：
#   SeqAllocVerticle registered with Mediate: nodeId=alloc-1, routerVersion=2
#   AllocManager initialized: nodeId=alloc-1, sections=21475, active=10738
```

**判定标准**：
- `registered with Mediate` — 注册成功
- `active > 0` — 活跃号段已就绪，可对外分配 seq

### 4.8 端到端发号验证（容器内 EventBus）

宿主机无法直连容器内 EventBus（IP 不可达），需要在容器内发起请求。最快的方式是从 alloc 容器内部发 fetchNext：

```bash
# 在 alloc-1 内部通过 Mediate HTTP 拿路由，验证 router 已收敛
docker compose exec seqsvr-alloc-1 sh -c '
  wget -qO- http://localhost:10106/router 2>/dev/null
'
```

若宿主机业务方（Logic-Server / pomelo 主应用）也作为容器加入 `pomelo-net` 网络，则可直接通过 `SeqClientService` 发号。见第六节。

---

## 五、停止与清理

```bash
# 停服（保留 volume 数据）
docker compose down

# 停服 + 清除全部数据（含 Postgres 数据库、StoreSvr mmap 落盘）
docker compose down -v
```

---

## 六、客户端接入（业务方容器化）

业务方（如 pomelo Logic-Server）作为容器接入同一 `pomelo-net` 网络后，使用 `SeqClientService` 即可自动完成路由发现与发号：

```java
// Logic-Server 中注入 SeqClientService
SeqClientService seqClient = SeqClientService.create(vertx, maxIdSize);

// 拿一条 seq（为 recipientId 的写扩散收件箱递增）
long seq = seqClient.fetchNextSequence(recipientId)
  .toCompletionStage().toCompletableFuture().get();
```

`SeqClientService` 内部逻辑：
1. 首次请求走 EventBus 广播到任意 AllocSvr，响应中附带路由表
2. 后续请求按路由表直连正确的 AllocSvr
3. 若路由过期（`ROUTE_OUTDATED`），自动重试一次（附带新路由表）

容器化接入需确保：
- `docker-compose.yml` 中 `networks: [pomelo-net]`
- `POMELO_REDIS_CM_ENDPOINT=redis://redis:6379`
- `VERTX_CLUSTER=true`

---

## 七、配置参考

| 系统属性 | 默认值 | 说明 |
|---|---|---|
| `seqsvr.store.replicaId` | — | StoreSvr 副本标识，决定 EventBus 地址 `seqsvr.store.<id>.*` |
| `seqsvr.store.replicas` | 单副本 | 逗号分隔副本列表 |
| `seqsvr.store.w` | 2 | 写仲裁数 |
| `seqsvr.store.r` | 2 | 读仲裁数 |
| `seqsvr.nodeId` | node-1 | AllocSvr 节点标识 |
| `seqsvr.mediate.enabled` | false | AllocSvr 是否接入 Mediate |
| `seqsvr.mediate.timeoutMs` | 3000 | Mediate 心跳超时（判定失联后迁移号段） |
| `seqsvr.leaseTimeoutMs` | 5000 | 租约失效 / pending 号段生效窗口 |
| `seqsvr.dataDir` | data/seqsvr | StoreSvr mmap + 路由表落盘目录 |
| `seqsvr.mediate.adminPort` | 10106 | Mediate admin HTTP 端口 |
| `POMELO_REDIS_CM_ENDPOINT` | — | Redis 集群管理器端点（**必须**） |
| `VERTX_CLUSTER` | false | 集群模式开关（compose 中设为 `true`） |
| `JAVA_OPTS` | `-Xms256m -Xmx512m` | JVM 参数（compose 中追加 `-D` 覆盖默认） |

---

## 八、常见问题

### Q: curl localhost:10106 无响应

A: 检查宿主 shell 是否设置了 `http_proxy`。若已设置，需加 `--noproxy "*"` 避免被代理拦截。

```bash
curl --noproxy "*" -s http://localhost:10106/router
```

### Q: alloc-2 的 active=0

A: alloc-2 可能只收到了 v1 路由（单节点），这是启动时序竞态——alloc-1 注册后路由更新到 v2，但 alloc-2 的注册响应中仍携带了 v1。重启 alloc-2 即可收敛：

```bash
docker compose restart seqsvr-alloc-2
```

### Q: Jib 构建报 "Failed to execute goal jib"

A: 常见原因：
1. **Maven Central 不可达** → 检查网络；若需代理，确认 `.mvn/jvm.config` 中代理参数正确
2. **基础镜像拉取失败** → 检查代理 5780 是否运行中，或切换基础镜像
3. **模块依赖未编译** → 先执行 `package -am` 再执行 `jib:dockerBuild`
4. **插件版本不存在** → 使用完整 goal：`com.google.cloud.tools:jib-maven-plugin:3.4.0:dockerBuild`

### Q: 如何缩容/扩容 AllocSvr

A: 当前版本需在 compose 中增减服务定义，然后重启 Mediate 触发路由重分配。生产环境建议配合 K8s + 仲裁自动感知节点变更。

### Q: StoreSvr 数据持久化在哪里

A: Docker named volume（`seqsvr-data-1/2/3`），映射到容器的 `/data` 目录。`docker compose down` 不删除，`docker compose down -v` 会清除。
