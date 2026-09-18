# 2026-09-17 seqsvr 剩余优化点评估与可观测性选型

- 日期:2026-09-17
- 类型:技术评估 + 实施计划
- 前置:与微信 seqsvr 两篇文章(52im thread-1998/1999)的差距分析已完成;persist-first 与租约不变量已在 `e6eb052`/`c80d971` 落地
- 状态:第一档第 2 项(可观测性)本期实施,其余待排期

## 一、剩余优化点(按性价比分档)

### 第一档:低成本、有真实风险,优先

| # | 项 | 现状依据 | 风险/收益 |
|---|---|---|---|
| 1 | **curSeqs 空闲驱逐** | `AllocManager.curSeqs` 只增不减(唯一回收=重启清零/号段收回);key 为哈希后收件人 id,约 50B/条;alloc 容器 `-Xmx512m` | 慢性内存增长被重启周期掩盖,百万级收件人即进入 OOM 区间。驱逐安全:驱逐后 id 从 baseSectionMaxSeqs 惰性重起步,与重启同语义,seq 只前进 |
| 2 | **可观测性指标** | 仅 /health(P5)+订阅自检(P2)+日志;事故 L3"35min 9 次租约跳闸"靠翻日志发现 | 本期实施,见第二节 |
| 3 | **compose healthcheck 接入** | depends_on 全是 service_started,仅 redis 用 service_healthy;alloc /health 已可报 serving+subscriptionOk | 纯配置改动,闭环 P6 启动风暴治理 |
| 4 | **Mediate 单实例运维定位** | 单容器;崩溃可自愈(init 从 Store 载路由 + alloc 3 次心跳失败重注册),但期间故障迁移冻结(alloc 死→号段无人接管) | 告警(mediate 断流>5min)+运维手册补节;Vert.x HA 双实例等有 SLA 要求再上 |

### 第二档:中等成本,等负载信号

| # | 项 | 说明 |
|---|---|---|
| 5 | 租约同步改"版本 ping" | 每 alloc 每 4s 向 R=2 副本拉完整路由表 JSON(syncLease),路由表一天变不了几次;Store 加 loadRouteTableVersion 轻量 RPC,版本不变不传 body |
| 6 | Store force() 攒批 | 每次批次提升=每副本一次 mmap fsync × W=2;pendingSaves 队列(c80d971)已有攒批落点,可做 5-10ms group commit。**先压测**——现有 benchmark 瓶颈在 PG,seqsvr 未打满 |
| 7 | 客户端 Mediate 拉取单飞 | pullRouterFromMediate 无 in-flight 去重,路由风暴时并发各拉一次;共享 in-flight Future 即可。同机多进程共享内存缓存不做(收益配不上复杂度) |

### 第三档:架构级,设计先行

| # | 项 | 说明 |
|---|---|---|
| 8 | 多 Set 部署 | 按**哈希区间**切 Set 可行:id=hash(key) mod maxIdSize 是确定性纯函数,把 [0, maxIdSize) 切 k 段,每段独立 store/mediate/alloc+地址前缀,客户端按区间选 Set 无需查表。失去的只是"相邻 uid 同 Set"语义(哈希后本来没有);代价是存量 mmap 文件按区间拆分迁移+客户端 Set 路由层。设计文档先行,等单集群逼近瓶颈 |
| 9 | Alloc 数据面脱离 Redis EventBus | 事故 L1 的结构性根源=数据面骑在 CM 订阅表上;RouterNode 已有 ip/port 字段。根治"僵尸可见性"类问题,但改动面大;库层订阅自愈(P1-P4)落地后风险已实质下降,先观察 |
| 10 | 负载感知均衡 | heartbeat 的 load 参数被忽略(MediateManager 只刷 lastSeen),balance 纯按 section 个数;哈希 id 天然均匀,等大群 key 热倾斜再做,依赖 #2 指标先行 |

**不做**:路由版本改 Redis INCR(P7 备选)——e6eb052 的"内存自增+Store 基线+失败跳号"已闭环且更少依赖。

## 二、可观测性选型:Vert.x Micrometer Metrics

**结论:Micrometer(指标)先落地;OpenTelemetry(tracing)第二阶段;vertx-zipkin 排除。**

三个候选回答的是不同问题:

| 方案 | 判定 | 理由 |
|---|---|---|
| vertx-zipkin | **排除** | Brave 版集成在 Vert.x 5 已移除(项目 5.1.5 无此模块);要 Zipkin 后端也应走 OTel exporter |
| vertx-tracing-opentelemetry | **第二阶段** | 回答"单个请求慢在哪/经过谁";需 collector+存储后端(Jaeger/Tempo)一整套新基础设施。等 gateway→logic→seqsvr→PG 跨服务延迟定位成为真实痛点再上(低采样率),与 Micrometer 可共存(MetricsOptions+TracingOptions 并行),EventBus send/reply 自动产生 span |
| vertx-micrometer-metrics | **本期** | 事故缺口全是 gauge/counter 形状(租约状态/积压/OUTDATED 率/订阅可见性),tracing 做不了"租约 15s 未恢复告警";指标+Prometheus alert 规则即告警路径,零新增后端 |

### 实施设计

- 依赖:`vertx-micrometer-metrics`(版本由 vertx-dependencies BOM 管理)+ `micrometer-registry-prometheus`
- 注册表:`SeqsvrMetrics`(pomelo-seqsvr-client 模块)持有 JVM 级单例 `PrometheusMeterRegistry`;alloc/mediate 依赖 client 模块,logic-server 亦传递可达
- 暴露:复用现有 admin HTTP——alloc 10105 / mediate 10106 的 requestHandler 加 `GET /metrics` 分支返回 `registry.scrape()`;logic-server 在 8888 ApiVerticle 加同路径。不加新端口
- 埋点(域指标为主):
  - alloc:`seqsvr.alloc.fetch.total{node}` / `seqsvr.alloc.fetch.duration` / `route.outdated.total` / `serving`(0/1) / `subscription.visible`(0/1,自检缓存值) / `lease.last.success.timestamp` / `sections.active` / `sections.pending` / `saves.pending` / `save.fail.total`
  - mediate:`nodes` / `router.version` / `router.regen.total` / `heartbeat.timeout.total` / `persist.fail.total`
  - client(logic):`route.version` / `outdated.consecutive` / `route.outdated.total` / `mediate.pull.total` / `fallback.total`
- 部署:compose 加 Prometheus 容器,抓取 `alloc-1/2:10105`、`mediate:10106`、`logic:8888` 的 /metrics;基础告警规则:serving==0 持续 30s、subscription.visible==0、mediate 指标断流
- 基数约束:label 只用 nodeId 等低基数值,**禁止**业务 id/section 索引入 label

### 基础告警规则(初始集)

1. `seqsvr_alloc_serving == 0` 持续 30s → alloc 停服(租约超时/初始化失败)
2. `seqsvr_alloc_subscription_visible == 0` → 事故 L1 类"僵尸可见性",自检已重注册仍告警
3. `up == 0` (mediate) 5min → 仲裁断流,故障迁移冻结
4. `rate(seqsvr_alloc_save_fail_total[5m]) > 0` → Store 写失败,防 seq 回退机制在工作
5. `seqsvr_client_outdated_consecutive >= 3` → 客户端路由收敛异常(版本倒退/分区)

## 三、后续排期建议

1 → 3 → 4 → 5-7(看压测数据)→ 8-10(设计评审)。第 1 项(curSeqs 驱逐)与第 2 项无依赖关系,可并行排。
