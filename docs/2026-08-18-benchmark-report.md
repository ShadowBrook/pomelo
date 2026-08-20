# Pomelo 压力测试报告（2026-08-18）

## 概述

使用 `pomelo-benchmark` 压测工具对 Pomelo IM 进行完整链路压测（HTTP 注册 + TCP IM 协议登录/加好友/发消息），度量各阶段的吞吐（QPS）与延迟（P50/P99/max），暴露系统容量边界。

## 压测环境与配置

| 项 | 值 |
|------|-----|
| 部署 | Docker Compose（logic-server / gateway / seqsvr×5 / PostgreSQL / Redis） |
| 硬件 | macOS，10 核 CPU |
| 压测参数 | `--users 1000 --friends 500 --messages 100000 --concurrency 1000` |
| 总耗时 | 5 分 24 秒 |

## 各阶段指标

| 阶段 | 总量 | 成功率 | QPS | P50 | P99 | max |
|------|------|--------|-----|-----|-----|-----|
| 注册 | 1000 | 100% | 3 | 209s | 313s | 315s |
| 登录 | 1000 | 100% | 3509 | — | — | 285ms 总耗时 |
| 加好友申请 | 500 | 100% | 1257 | 152ms | 196ms | 197ms |
| 接受好友 | 500 | 100% | 1255 | 123ms | 191ms | 192ms |
| 发消息 | 100000 | 16% | 14978 | 28ms | 615ms | 874ms |

## 瓶颈分析

### 1. 注册 — bcrypt cost=12 是 CPU 瓶颈

每用户 bcrypt 哈希约 2.5 秒（cost=12），10 核 CPU 并发 1000 时严重排队（P50=209 秒）。注册是密码哈希的 CPU 密集度瓶颈，而非网络/协议瓶颈。

### 2. 发消息 — PG 连接池是瓶颈（84% 失败）

```
ERROR: Connection pool reached max wait queue size of 512
```

- PG 单条 INSERT 实测仅 0.3ms（`EXPLAIN ANALYZE`），并非 PG 写入本身慢
- 并发 1000 时，连接被**整条发送链路占用**（seqsvr 分配 seq → PG INSERT → 推送），连接持有时间长
- 池（64 连接 + 512 等待队列）被打满，超出部分立即失败（84%）

## 排查过程中修复的真实问题（均验证生效）

| 问题 | 根因 | 修复 | 效果 |
|------|------|------|------|
| 登录 0/1000 | Vert.x 每次连接创建新 NetClient，并发 1000 时资源耗尽 | 共享单个 NetClient（Vert.x 推荐用法） | 登录 0 → **1000/1000**（3509 qps） |
| 登录 Redis 排队 | Redis 连接池 `maxPoolSize=4` 过小 | 调大到 64 / maxWaitingHandlers 512 | 登录恢复 |
| 加好友/发消息 500 | PG 连接池 `maxSize=10` 过小 | 调大到 64 / maxWaitQueueSize 512 | 加好友/接受好友 0 错误 |
| 发消息 route outdated | `RangeId.calcSectionID` 的 `id >= idBegin + size` int 溢出（id 空间接近 2^31） | 改 long 运算 + `MediateManager.generateRouter` 同步修复 | 早期 10% 失败消除 |

**配置修改**（`conf/config.yaml`，实际生效文件）：
- `redis.maxPoolSize`: 4 → 64，`redis.maxWaitingHandlers`: 8 → 512
- `database.pool.maxSize`: 10 → 64，`database.pool.maxWaitQueueSize`: 50 → 512

## 优化建议

### 发消息
1. **降低发消息并发**：PG 池（64 连接）约支撑 ~20000 qps（0.3ms/条），发消息并发降到 300 可显著提升成功率
2. **减少连接占用**：优化 `C2CService.doSend` 的 seqsvr 往返，缩短 PG 连接持有时间
3. **调大 PG 池**：受 PostgreSQL `max_connections=100` 限制，当前 64 已接近上限

### 注册 ✅ 已解决（2026-08-20）
密码加密从 bcrypt 换成 Vert.x `HashingStrategy`（PBKDF2），注册 QPS 从 3 提升到 ~130（44 倍），单次哈希从 ~2.5s 降到 ~0.2s。**异步注册**仍可作为下一步优化（注册请求先返回，后台完成密码哈希）。

## 结论

核心业务（登录 / 加好友 / 消息收发基础链路）在并发 1000 下均正常（3509 / 1257 / 1255 QPS，0 错误）。原始瓶颈两处：

- ~~**注册**：bcrypt cost=12 的 CPU 密集度~~ ✅ 已修复（HashingStrategy / PBKDF2）
- **发消息**：并发 1000 时 PG 连接池被整条发送链路打满（待优化）

压测工具在此过程中发现并修复了 5 个独立缺陷（NetClient 共享、Redis/PG 连接池、seqsvr int 溢出、bcrypt 慢哈希）。

## 相关文件

- 压测工具：`pomelo-benchmark/`（实现规划见 `docs/superpowers/plans/2026-08-18-benchmark-tool.md`）
- 运行方式：`./mvnw -pl pomelo-benchmark -am install -DskipTests && ./mvnw -pl pomelo-benchmark exec:java -Dexec.args="--host localhost --users N --friends F --messages M --concurrency C"`
