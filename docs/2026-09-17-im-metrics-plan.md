# 2026-09-17 IM 业务指标扩展设计（消息量 / 延迟 / 推送链路）

- 日期:2026-09-17
- 类型:可观测性设计 + 实施记录
- 前置:观测选型（Micrometer vs OTel vs Zipkin）与 seqsvr 域指标已落地，见 `docs/2026-09-17-seqsvr-optimization-plan.md`
- 状态:实施中

## 一、基础设施归位

业务指标复用 seqsvr 已建的 Micrometer + Prometheus 体系，但埋点归属业务模块，基础设施先做两处归位：

1. **指标门面上移 pomelo-common**：新建 `PomeloMetrics`（registry 单例 + counter/timer/gauge 工厂 + Vert.x 内建指标工厂），删除 seqsvr-client 的 `SeqsvrMetrics`，seqsvr 调用点全部替换。gateway 经 pomelo-common 传递获得 micrometer。
2. **gateway 接入观测**：`GatewayMain` 挂 metrics 工厂（免费获得 vertx 内建 TCP/WS/HTTP 指标）；gateway 新增 admin HTTP `GET /metrics`（端口 `gateway.metrics.port`，默认 10104，绑 127.0.0.1）。

## 二、指标清单

| 指标 | 类型 | 埋点位置 | 说明 |
|---|---|---|---|
| `im.message.sent.total{type=c2c\|c2g, result=ok\|fail}` | Counter | `C2CService.doSend` / `C2GService` 发送 Future 完成回调 | 消息量主指标 |
| `im.message.process.latency` | Timer | 同上，完成回调中 record | 服务端处理延迟（收包→持久化+推送完成） |
| `im.push.e2e.latency` | Timer（record 计算差值） | gateway 推送投递成功处：`now - created_at` | 服务端→接收端投递延迟 |
| `im.push.delivery.total{mode=precise\|broadcast}` | Counter | `PushRouter` | 投递模式计数；`mode=broadcast` 即降级路径（路由缺失/节点已死），广播占比是路由表健康度的直接信号 |
| `im.pull.request.total` | Counter | `PullService` | 离线同步压力 |
| `im.connections` | Gauge | gateway `SessionRegistry` | 在线连接数 |
| `seqsvr.alloc.fetch.duration` | Timer（已有） | `AllocManager` | 取号延迟 |

### 告警规则（新增）

- `increase(im_push_delivery_total{mode="broadcast"}[5m]) > 0` → 推送降级广播（路由缺失或目标节点死亡），路由表健康度信号

## 三、设计约束

1. **基数红线**：label 只用 `type`/`result`/`mode` 等枚举值，禁止 userId / conversationId / msgId 入 label（按会话的统计属 OLAP，不进指标系统）。
2. **响应式计时位置**：Timer 在 Future 完成回调里 record（`onComplete`/`onSuccess`），方法返回 ≠ 处理完成。
3. **端到端延迟测法**：不在单段代码里用 Timer 包住；以消息落库时间戳（`MessageRecord.created_at`）为起点，gateway 投递成功时 record 差值（服务端→接收端段，最有价值的一段）。
4. **延迟分位数**：业务延迟 Timer 开 `publishPercentileHistogram()` 输出 histogram 桶，P95/P99 可算，桶基数固定。

## 四、与 benchmark 的关系

`pomelo-benchmark` 量客户端视角（压测期到达率/延迟），Prometheus 量服务端持续视角；前者找容量边界，后者管线上告警，互补不可替代。

## 五、部署与可视化记录

- Prometheus 与 Grafana 均在 docker-compose（127.0.0.1:9090 / 127.0.0.1:3000，匿名只读，编辑用 admin）
- 抓取目标：`seqsvr-alloc-1/2:10105`、`seqsvr-mediate:10106`、`gateway:10104`、`pomelo:8888`（logic 为 TLS，需 `scheme: https` + `insecure_skip_verify`）
- 看板「Pomelo IM 总览」由 `deploy/grafana/` provisioning 自动加载，时区跟随浏览器；改动 prometheus.yml/alerts.yml/看板 JSON 后需 `docker compose up -d --force-recreate prometheus grafana`（单文件 bind mount 绑定 inode，restart 不会重新解析）
- Prometheus 原生 UI 图表恒为 UTC（Go 标准库限制，容器 TZ 无法生效）；看本地时间曲线用 Grafana
