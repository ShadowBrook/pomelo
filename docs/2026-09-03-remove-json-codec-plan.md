# 移除 JSON codec、统一 Protobuf 实施计划

> 日期：2026-09-03
> 状态：草案（待评审）
> 范围：IM 消息线（wire 协议 + 消息体编解码）；**不含** REST /api 的 HTTP JSON、seqsvr 内部 `RouterJsonCodec`
> 关键前提：线协议 `codecId` 字节**暂时保留**，仅冻结语义为"恒 = 0（Protobuf）"，最终下线另立任务

## 一、目标与动机

当前消息线是"双 codec 并存"：`ImMessage.codecId` = 0 走 Protobuf，= 1 走 JSON。
服务端每个 Service 都要维护 **PB / JSON 双分支**、推送前还要**按收件人 codec 协商**组包
（分布式 `user-codecs` map），前端 pomelo-web 目前主动用 JSON。为了收敛实现复杂度、
统一序列化，决定：

1. 消息线只保留 **Protobuf** 一种编码。
2. `ImMessage` / `PushEnvelope` 线内 `codecId` **保留不删**（兼容老包、为将来留扩展位），
   但语义冻结为"只支持 0"。
3. 删掉所有 JSON 编解码分支、`JsonCodec`、按收件人协商 JSON 的逻辑。
4. 收尾（真正从 wire 移除 codecId 字节）**不做**，留后续任务。

## 二、现状地图（codec 双轨全触点）

### 2.1 服务端

| 层 | 文件 | 现状 | 处理 |
|----|------|------|------|
| 消息体编解码 | `pomelo-common/.../codec/JsonCodec.java` | `codecId=1`，Jackson 序列化任意 POJO | 删除 |
| | `pomelo-common/.../codec/CodecRegistry.java` | `cmd#codecId` 二维注册，JSON 分支 | 收敛为 PB 单维；`getCodec` 忽略 codecId 恒走 PB |
| | `pomelo-common/.../codec/ProtobufCodec.java` | PB 路径 | **保留**（含静态 Parser[256]） |
| | `pomelo-common/.../codec/MessageCodec.java` | 接口含 `getCodecId()` | 后续随清理移除 |
| 线协议头 | `pomelo-common/.../common/ImMessage.java` | `codecId` 占 1 字节读写字 | **保留字节**，语义冻结为 0 |
| 逻辑层注册 | `pomelo-logic-server/.../codec/CodecRegistryHolder.java` | 每条 cmd `registerProtobuf + registerJson` 两行 | 删全部 `registerJson` |
| Service 基类 | `pomelo-logic-server/.../service/ServiceBase.java` | `encodeBody` JSON 分支、`dualBody`、`buildErrorResp` 双格式、`decode(codecId)` | 去 JSON，`decode` 恒走 PB DTO 映射 |
| Service 实现 | `C2CService / C2GService / AckService / AuthService / CtrlService / FriendService / GroupAckService / GroupManagementService / GroupPullService / PullService / UploadService` | `dualBody(...)` 或 JSON 分支；`buildResponse` 回声 `request.getCodecId()` | 去双分支；响应 codecId 恒 0 |
| 请求上下文 | `logic/model/C2CReqContext.java`、`GroupMsgContext.java` | 携带 codecId 用于回声 | 移除字段 |
| 推送协商 | `C2CService.publishC2CNotify`、`C2GService`、`AckService`、`GroupManagementService` | `routeTable.resolveCodec(recipient)` → 按用户 codec 组 PB/JSON push | 只组 PB；去 resolveCodec |
| 路由表 | `pomelo-common/.../config/SessionRouteTable.java` | 写/读 `__pomelo.user-codecs`、`setCodec/resolveCodec` | 停写；resolveCodec 恒 0 → 后删 map 与方法 |
| 网关 | `gateway/handler/SessionRegistry.java` | Session 存 `codecId` | 字段改为恒 0 → 后删 |
| | `gateway/handler/MessageDispatcher.java` | 认证时 `routeTable.setCodec(...)`；错误响应按 codecId 分支 | 去 setCodec；错误恒 PB |
| 内部推送信封 | `pomelo-common/.../model/PushEnvelope.java`、`PushCodec.java` | EventBus 上携带 codecId 字节 | 去字节（内部格式，可安全删） |
| Benchmark | `pomelo-benchmark/.../benchmark/ImClient.java` | 见其 codec 使用 | 统一 PB |

### 2.2 测试

`JsonCodecTest`（删）、`ImMessageTest` / `TcpGatewayVerticleTest` / `C2CServiceTest` /
`UploadServiceTest` / `PullServiceMediaSignTest` / `GroupPullServiceMediaSignTest` /
`CodecSizeComparisonTest`（未入库草稿）—— 逐一见 4.5。

### 2.3 前端 pomelo-web

纯 JSON：`sdk/types.ts`（`CODEC_JSON=1`）、`sdk/protocol.ts`（`JSON.stringify/parse` +
写死 `CODEC_JSON`）、`sdk/client.ts`（每个 cmd 用散装 JSON object 作 body、按 cmd 透传
解包）、各 store / friend / group 直接消费这些对象。**当前无任何 proto 运行时**。

## 三、依赖关系决定迁移顺序

推送链路按**收件人 codec**（登录连接写入的 `user-codecs` map）组包：只要 web 还以
JSON 登录（map=1），服务端就不能关 JSON 推送，否则 web 收不到实时消息。而 web 的
PB 通道服务端**已完整支持**（每个 cmd 都有 `registerProtobuf` + DTO 映射）。

因此顺序必须是：

```text
P0 web 客户端切换到 PB（自选 codecId=0）
   ↓（此时服务端 PB 全链路已可用，JSON 仅剩"没人用"的路径）
P1 服务端收敛：解码/编码/推送只走 PB，删 registerJson 与双分支
   ↓
P2 网关/会话/路由表去 codec 协商
   ↓
P3 删除与清理（JsonCodec、测试、依赖、内部信封字节）
   ↓
P4（后续单独任务）wire 下线 codecId 字节 —— 本次不做
```

## 四、分阶段步骤

### P0：pomelo-web 切到 Protobuf（工作量大头，独立可合入）

目标：web 用 codecId=0，所有 C→S 请求体与 S→C 响应/推送体均按 proto 解码，行为与
现状逐字节一致（消息收发、ACK、拉历史、好友、群、媒体上传）。

1. **选型并引入 JS proto 运行时**（决策 D2，见 §5）：推荐 `protobufjs` + `pbjs/pbts`
   静态生成 `src/sdk/proto/*.js|.d.ts`；由 `pomelo-common/src/main/proto` 全部 domain
   proto 生成（注意跨包 import，需带 `-p` 指向 proto 根）。
   - 仓库目前**没有** JS proto 生成器（CLAUDE.md 提到的 test-resources 脚本不存在），
     需在 pomelo-web 新建生成链路（写 `scripts/gen-proto.mjs` + package.json 脚本）。
2. **`sdk/types.ts`**：`CODEC_JSON=1` → 常量改 0（改名 `CODEC_PROTOBUF`）；各 cmd 枚举
   已有；新增"cmd → MessageType"解析表类型。
3. **`sdk/protocol.ts`**：`encode` body 改为 `Message.encode().finish()`，codecId 字节写 0；
   `decode` 返回原始 bytes + 头部信息（不再 JSON.parse）。
4. **`sdk/client.ts`**：为每个下行 cmd（RESP/NOTIFY/PULL/ACK 等）建 PB parser 解码表；
   把散装请求 body（C2C/C2G/ACK/PULL/friend/group/upload/auth/logout）改为对应 proto
   message。字段从服务端 proto 的 **snake_case** 取值，需要一层归一化到现有 camelCase
   TS 类型（新增 `normalize` 帮助函数，收敛在 sdk 内，store 侧尽量少改）。
5. **store / hook**：若归一化在 sdk 完成，`useChatStore`/`useGroupStore`/friend 等只做
   签名/取值适配，逐项验证（发文本、图片、收 push、断线拉增量、好友、群、媒体）。
6. **DoD**：`npm test`、`npm run build` 通过；本地起服务端 + dev 前端手工过一遍核心链路；
   登录后服务端 `user-codecs` 里该用户 codec 应为 0（可用 Redis / 日志验证）。

### P1：服务端解码 / 编码 / 推送只走 PB

前置：P0 已让唯一在用客户端(codec=1) 消失，可放心收敛。

1. **`CodecRegistryHolder`**：删除全部 `registerJson(...)` 行（共 ~11 处：auth/c2c/c2g/
   pull/group-pull/ack/group-ack/ctrl/friend-search/friend-add 等）。
2. **`ServiceBase`**：
   - `decode()`：恒按 PB（`getCodec(cmd, 0)`）；请求 codecId 不再参与选择。
   - `encodeBody()`：删 JsonObject / mapFrom 分支，只留 protobuf encode。
   - `dualBody()` 删除，调用处改成直接构造 PB 响应体。
   - `buildErrorResp()`：只组 `CommonProto.ErrorBody`。
3. **各 Service**：清除 `dualBody(codecId, ...)` 与 `codecId == ...` 分支（§2.1 列表）；
   `buildResponse` 里 `codecId(request.getCodecId())` → 恒 `0`（保留字节，值写 0）。
   上传相关（`UploadService`）若此前有 JSON 分支一并清理。
4. **推送组包**（`publishC2CNotify` / C2G / Ack / GroupManagement push）：删除
   `routeTable.resolveCodec(recipient).onSuccess(recipientCodec -> {...})` 分支，直接按
   PB 组 `C2CNotify`/`C2GNotify`/…；构造 `PushEnvelope` 不再传 codec。
5. **DoD**：`./mvnw clean test` 全绿；服务端仍能对旧(未删的)PB 客户端工作。

### P2：网关 / 会话 / 路由表去 codec 协商

1. **`MessageDispatcher`**：认证成功后去掉 `routeTable.setCodec(userId, platform, codecId)`；
   错误响应两处 codecId 分支 → 恒 PB。
2. **`SessionRouteTable`**：删 `CODEC_MAP_NAME`、`setCodec/resolveCodec`（或先让
   `resolveCodec` 返回 `Future.succeededFuture((byte)0)`，观察无异常后删除方法 +
   `__pomelo.user-codecs` 相关读写与清理逻辑）。`platform` 若无他用一并移除。
3. **`SessionRegistry`**：`Session.codecId` 移出构造参数与存储；`getCodecId()` 恒 0 或删。
4. **DoD**：单测 `TcpGatewayVerticleTest` 等通过；登录上线日志不再出现 codec=1。

### P3：删除与清理

1. 删 `JsonCodec.java`、`JsonCodecTest.java`；`CodecSizeComparisonTest.java`（未入库）按
   结论处置（若后续要 JSON 对比就留，但既然弃用建议删）。
2. **`CodecRegistry`**：删 `registerJson`、`cmd#codecId` 二维 key 收成 cmd 单 key，
   `getCodec(cmd, codecId)` 忽略 codecId（或改为只吃 PB 参数），必要时简化 `buildKey`。
3. **`MessageCodec`**：删 `getCodecId()`。
4. **`PushEnvelope` / `PushCodec`**：去掉 codecId 字节与字段（内部 EventBus 格式，两端
   同仓同步改）。
5. **Benchmark `ImClient`**：统一 PB（若它写死 JSON 则改；若已有 PB 开关则固定 PB）。
6. 依赖：确认 vert.x `io.vertx.core.json` 是否仍被 config / REST 使用 —— 保留（不能删
   Jackson），只删"JSON 当消息体 codec"这条路。
7. **DoD**：全仓 grep 无 `registerJson` / `JsonCodec` / `dualBody` / `resolveCodec` 引用；
   `./mvnw clean test` + benchmark 跑通。

### P4（后续，单独立项，不在本次执行）

真正把 `ImMessage` wire 中 codecId 字节拿掉或借 protocol `version` bump 收紧读取校验。
前置：所有客户端确认走 PB 且无灰度尾巴。

## 五、需要拍板的决策

| # | 问题 | 建议 |
|---|------|------|
| D1 | 服务端收到 `codecId != 0` 的包：忽略按 PB 解析，还是校验报错？ | **忽略按 PB 解析**（过渡期不误伤）；待 P4 收紧为校验 |
| D2 | web 切 PB 的运行时/生成方式（P0 大项） | protobufjs 静态生成 `src/sdk/proto`；或用 `@bufbuild/protobuf + buf`（TS 原生、类型更好，但工具链更重）。若 pomelo-web 近期有重写计划，P0 可整体顺延——但 P1 之前必须先落 |
| D3 | 内部 `PushEnvelope/PushCodec` 的 codecId 字节是否一并删 | **删**（内部格式，成本低） |
| D4 | seqsvr `RouterJsonCodec`（跨节点内部 JSON） | **不在本计划范围**，保留；如需统一另立任务 |

## 六、风险与回滚

- **推送断崖**：P1 一旦把 push 全切 PB，仍在用 JSON 的客户端收不到实时消息。缓解 =
  顺序依赖 P0 先完成；P0/P1 各自独立可合入、可在任意一步回退（回到双 codec 也仅是
  恢复已删分支）。
- **web 迁移回归面大**：client.ts 各 cmd body 全改。缓解 = sdk 内做归一化收敛、每类操作
  补 vitest、P0 DoD 用手工 E2E 把关核心链路。
- **codec map 残留**：老连接在旧网关进程里写入的 `user-codecs` 条目有 TTL，重启/换代后
  自然过期；P2 删除写路径即可，无需手工清。
- **字段命名翻转**：PB 走 snake_case，与现 web camelCase 不同 → 归一化 helper 是重点，
  漏一处就静默丢字段，靠测试覆盖（尤其 media content JSON、friend notify、群已读）。

## 七、测试计划（关键用例）

- 服务端单测：P1 后每条 cmd 请求仍能被正确 PB 解码 + DTO 映射（`C2CServiceTest` 等回归）。
- 推送单测：`publishC2CNotify` 只产 PB bytes、无 JSON 分支；错误响应为 `ErrorBody`。
- 网关单测：握手/认证（登录 codec 上报 0）、心跳、PUSH 下发改 PB。
- web vitest：protocol encode 字节码与 codecId=0、按 cmd 解码、归一化 helper、
  friend/group/media 相关 store 取值。
- E2E（P0/P1 收尾各跑一次）：双用户文本互发 + ACK/已读、图片上传与拉取、断线重连增量、
  好友添加推送、群聊收发与已读成员。
