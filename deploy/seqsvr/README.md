# seqsvr Docker 部署

分布式发号服务（StoreSvr / AllocSvr / MediateSvr）的容器化部署。

## 组件与拓扑

```
                     ┌──────────────┐
   业务方/客户端 ────▶│  Redis       │◀── 集群 EventBus（所有服务经 Redis 通信，无直连）
   (seqsvr client)   └──────────────┘
                          │ pub/sub
   ┌─────────┐  ┌─────────┐  ┌─────────┐
   │store-1  │  │store-2  │  │store-3  │  StoreSvr 副本（NRW：写 W=2 / 读 R=2）
   └────┬────┘  └────┬────┘  └────┬────┘
        └────────────┼────────────┘
                ┌────┴────┐
                │mediate  │  MediateSvr 仲裁（路由表生成/故障迁移） + admin HTTP :10106
                └────┬────┘
          ┌──────────┴──────────┐
          │alloc-1              │alloc-2   AllocSvr 分配层（互为备机，均衡分号段）
          └─────────────────────┘
```

## 启动

已整合到根 `docker-compose.yml`（与主应用/数据库/Redis 同一网络 `pomelo-net`）。

```bash
# 1. 宿主机先产出三个服务的 fat jar（本环境 Maven Central 被 403，容器内下载依赖不可行；
#    宿主机依赖 ~/.m2 缓存可构建）
./mvnw -DskipTests package -pl pomelo-seqsvr/pomelo-seqsvr-store \
  -pl pomelo-seqsvr/pomelo-seqsvr-server \
  -pl pomelo-seqsvr/pomelo-seqsvr-mediate -am

# 2. 仅启动 seqsvr（复用根 redis，不启动主应用 pomelo）
docker compose up -d --build redis seqsvr-store-1 seqsvr-store-2 seqsvr-store-3 \
  seqsvr-mediate seqsvr-alloc-1 seqsvr-alloc-2

# 全部启动（含主应用）
docker compose up -d --build

# 状态
docker compose ps
```

首次构建需拉基础镜像（宿主机需能访问 Docker Hub；本环境已配 daemon 代理）。seqsvr 镜像为
`deploy/seqsvr/Dockerfile`（宿主机构建 fat jar + COPY，与根 Dockerfile 约定一致）。

## 校验

```bash
# Mediate admin HTTP：路由表 / 节点存活
curl http://localhost:10106/router     # {"version":3,"nodeList":[alloc-1 分号段..., alloc-2 分号段...]}
curl http://localhost:10106/nodes      # {"nodes":2,...}

# 日志
docker compose logs -f seqsvr-alloc-1
```

路由表 version ≥ 1 且 `nodeList` 含 2 个 AllocSvr 即注册成功；`alloc-1` 日志出现 `registered with Mediate` 即分配号段完成。

## 配置项（JAVA_OPTS 系统属性 / POMELO_* 环境变量）

| 配置 | 默认 | 说明 |
|---|---|---|
| `seqsvr.store.replicaId` | r1 | StoreSvr 副本标识，决定其专属 EventBus 地址 `seqsvr.store.<id>.*` |
| `seqsvr.store.replicas` | 单副本 | 逗号分隔的副本地址前缀（NRW 客户端用） |
| `seqsvr.store.w` / `seqsvr.store.r` | 2 / 2 | 写/读仲裁数 |
| `seqsvr.nodeId` | node-1 | AllocSvr 节点标识 |
| `seqsvr.mediate.enabled` | false | AllocSvr 是否接入 Mediate（本 compose 为 true） |
| `seqsvr.mediate.timeoutMs` | 3000 | Mediate 心跳超时（判定失联迁移） |
| `seqsvr.leaseTimeoutMs` | 5000 | 租约失效 / pending 号段生效窗口 |
| `seqsvr.dataDir` | data/seqsvr | StoreSvr mmap + 路由表落盘目录（compose 挂载 volume） |
| `POMELO_REDIS_CM_ENDPOINT` | - | Redis 集群管理器端点（必须） |
| `VERTX_CLUSTER` | false | 集群模式开关（compose 设为 true） |

## 客户端接入（重要）

业务方（Logic-Server / Gateway）用 `SeqClientService`，通过 Redis 集群 EventBus 访问
`seqsvr.alloc.<nodeId>.fetchNext`（按路由表路由）。路由表在响应中旁路返回，客户端自动收敛。

**客户端必须与 seqsvr 服务在同一 Docker 网络内**：
- vertx-redis-clustermanager 的 EventBus 消息投递是**直连目标节点的广播 IP**（容器内网 IP，
  如 `172.19.0.x`），不是经 Redis 转发。
- 宿主机（或网络外的进程）无法直连容器 IP，即使 `POMELO_REDIS_CM_ENDPOINT` 指向同一 Redis 也收不到。
- 因此业务方要作为容器加入 `seqsvr-net` 网络（`networks: [seqsvr-net]`），并设置
  `POMELO_REDIS_CM_ENDPOINT=redis://redis:6379`、`VERTX_CLUSTER=true`。
- 集群内（store↔mediate↔alloc）跨容器 EventBus 已由本 compose 启动验证通过。

## 备注

- 三个服务镜像共用 `deploy/seqsvr/Dockerfile`，`--build-arg MODULE_PATH` 区分。
- 群消息（`im_message_group`）尚未接入 seqsvr，schema 中 `seq` 为设计保留。
- `.dockerignore` 未排除 `src/main/proto/`（Docker 内构建需 protoc 重新生成）；已排除 `**/target/*`。
