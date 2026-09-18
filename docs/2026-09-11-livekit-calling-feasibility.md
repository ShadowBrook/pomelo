# Pomelo 接入 LiveKit 实现音视频通话 — 调研与计划

- **日期**：2026-09-11
- **结论先行**：**可行，推荐接入自托管 LiveKit**。信令复用现有 IM 通道（新增 CALL_* 命令），媒体面完全交给 LiveKit SFU，Pomelo 后端只做"通话状态机 + 签发入会凭证"。1:1 音视频 MVP 预计 1.5~2 周。
- **资料来源**：docs.livekit.io（transport / self-hosting / generating-tokens / webhooks）、Maven Central（io.livekit:livekit-server）、client-sdk-js GitHub，抓取时间 2026-09-11。文中标 ⚠ 的项是文档未直接覆盖、需在 Spike 阶段实测确认的。

---

## 一、LiveKit 是什么

开源（Apache-2.0）的 WebRTC **SFU**（Selective Forwarding Unit）实时媒体服务器，Go 编写，单二进制/Docker 镜像分发：

- **传输架构**（docs.livekit.io/transport/）：客户端通过 WebSocket 走**信令**完成加入房间/发布订阅协商，**媒体**走 WebRTC（ICE 协商，优先 UDP，失败回退 TCP，再回退 TURN/TLS）。SDK 屏蔽全部协商细节，业务只拿事件和 track。
- **拓扑**：每个房间固定由一台 SFU 服务（single SFU per room），所有人把流推给 SFU，SFU 转发给订阅者。1:1 通话与万人直播是同一套机制，规模只是加机器的问题。
- **配套能力**：多端 SDK、服务端房间管理（RoomService REST）、Webhook 事件、Egress 录制/转推、Ingress、SIP 电话接入、E2EE。

### 与自建方案对比（为什么选它）

| 方案 | 要自己做的事 | 评估 |
|---|---|---|
| 纯 mesh P2P（无 SFU） | 信令、ICE、NAT 穿透、重连、质量自适应全自建 | 1:1 看似省事，但 TURN 部署、弱网、移动端兼容都是深坑，2 人以上立刻要 SFU |
| mediasoup / Janus / Pion 裸写 | SFU 转发逻辑、房间管理、带宽估计都要自己写 | 灵活但工程量大数倍，等于再造半个 LiveKit |
| **LiveKit 自托管** ✅ | 只写业务状态机；媒体、重连、质量自适应、多端 SDK 全部现成 | 与本项目"Docker Compose 自托管"部署形态完全一致 |
| LiveKit Cloud | 零运维 | 媒体数据出内网、按量计费；内网 IM 场景不合适 |

---

## 二、关键事实核查（对接入方案有直接影响的）

### 2.1 服务端 SDK（Java 可用）

- Maven 坐标：`io.livekit:livekit-server`（当前 0.15.1），即 Kotlin/JVM SDK，提供 `AccessToken`（签发入会 JWT）、`RoomServiceClient`（房间管理 REST）、`WebhookReceiver`（校验 webhook 签名）。
- **依赖冲突警告**：它传递依赖 `protobuf-java 4.31.1` 之下的 `4.29.4`、`jackson-databind 2.22.2`、`com.auth0:java-jwt 4.6.0`。本项目根 pom 钉了 protobuf **4.31.1** / jackson **2.21.x**，直接引入会打架（protobuf 双版本在同一 classpath 是真实风险）。
- **应对**（推荐 Plan A）：1:1 通话阶段**不引 SDK**——
  - 入会凭证就是普通 JWT（HS256，claims：`iss=apiKey`、`sub=identity`、`exp/nbf`、`video: {roomJoin, room, canPublish, canSubscribe}`），用 pomelo-common **现成的 JWTAuth（Vert.x）** 就能签，十分钟的事；
  - 房间管理走 Twirp 的 JSON 模式（LiveKit RoomService 是 Twirp 协议，接受 `application/json`），用 Vert.x WebClient 封装 `CreateRoom / DeleteRoom / ListRooms` 三个调用即可。
  - 等后续要用 Egress 录制等重能力时再评估引 SDK 并统一 protobuf 版本。

### 2.2 访问凭证（token）

- JWT 三要素：API key/secret（自托管在 livekit.yaml 里配置 devkey）、identity（用 pomelo 的 userId）、video grants。
- 常用 grants：`roomJoin` + `room`（房间名，join/admin 时必填）、`canPublish`、`canSubscribe`。主叫/被叫都给 publish+subscribe；后续只看直播的场景才用 subscribe-only。
- TTL 只影响**首次连接**；连上后 SDK 自动续期（续期 token 有效期 = 10 分钟与原 token 剩余寿命的较大者）。通话场景给 10~15 分钟足够，挂断即作废。

### 2.3 自托管与端口

官方 VM 部署文档给出的端口面：

| 端口 | 协议 | 用途 | 本项目是否需要暴露 |
|---|---|---|---|
| 7880 | HTTP/WS | 信令（SDK `room.connect(ws://host:7880, token)`） | 浏览器需可达；生产建议反代到主域 `wss://域名/livekit` |
| 50000-60000/UDP | WebRTC | 媒体主通道 | 必须暴露（公网） |
| 7881/TCP | WebRTC | UDP 不通时的 TCP 回退 | 建议暴露 |
| 3478/UDP | TURN/UDP | 中继 | 建议暴露 |
| 443/TCP | HTTPS + TURN/TLS | 反代与最严防火墙下的最终回退 | 生产需要域名+证书 |

- 配置文件 `livekit.yaml`：`port`（信令）、`rtc` 块（`use_external_ip` / `nat_1_to_1_ip`、UDP 端口范围、TCP 回退）、`turn` 块、`keys`（API key/secret）、`webhook` 块（`api_key` + `urls`）、`room.empty_timeout` / `max_participants`。
- NAT 是自托管最大运维点：`rtc.use_external_ip: true` 让服务器自报公网 ICE 候选；不配对会出现"同一内网能通、跨网单向黑屏"这类问题。
- 官方镜像多架构（linux/amd64 + arm64）⚠：与本项目 arm64 Jib 镜像共存于同一台 Docker 宿主机应当无碍，Spike 时 `docker pull` 实测确认。

### 2.4 Webhook（掉线兜底的权威事件源）

- 事件：`room_started / room_finished / participant_joined / participant_left / participant_connection_aborted / track_published ...`。
- 请求是 POST，`Content-Type: application/webhook+json`，`Authorization` 头是带 **body sha256** 的 JWT（用 API key 签），接收端用 secret 验签——**必须验**，否则任何人 POST 伪造"对方已挂断"就能拆散通话。
- 投递是推送、无送达保证（失败重试数次、事件有序）。所以 webhook 只做**兜底清理**（进程被杀、断网未发 hangup），主路径仍是客户端显式 hangup + 服务端超时器。

### 2.5 浏览器客户端

- 核心包 `livekit-client`（框架无关）：`new Room()` → `room.connect(url, token)` → `RoomEvent.TrackSubscribed` 里 `track.attach()` 到 `<video>/<audio>`；发布用 `setMicrophoneEnabled / setCameraEnabled`。
- 官方 React 组件包 `@livekit/components-react` 另行维护。**本项目不采用**：其一，React 19 兼容性未证实（npm 页面反爬没拿到 peerDeps ⚠）；其二，本项目 UI 全部深度自绘（消息气泡、语音波形都是自己画的），组件库的价值有限。直接用 `livekit-client` + 自绘通话浮层，与现有代码风格一致，也少一层 peer-dependency 风险。

---

## 三、与 Pomelo 的对接设计

### 3.1 总体分工

```
信令/业务面（复用现有 IM 通道）              媒体面（新增）
┌──────────┐  CALL_* 命令  ┌──────────────┐   ┌─────────────────┐
│ pomelo-web│ ───────────→ │ logic CallService│   │ livekit-server   │
│ (livekit- │ ←─────────── │ 状态机+鉴权+token │   │ (SFU, 新容器)    │
│  client)  │  CALL_* 推送  │ (PushRouter 推送) │   │ WS 7880 信令      │
└────┬─────┘               └──────┬───────┘   │ UDP 媒体          │
     │                            │ Twirp-JSON / webhook        │
     └────── ws://livekit:7880 ───┴─────────────┘
          携带 logic 签发的 JWT 直连 SFU
```

Pomelo 后端**不经手任何媒体**，职责收敛为： friend 校验、忙闲判定、通话状态机、签发 token、房间生命周期兜底、通话记录落库。这与网关"身份可信链"的设计一脉相承——token 只发给通过认证的用户，room 名服务端签发，客户端无法伪造会话对象。

### 3.2 命令与协议（proto/call）

新增 `proto/call/call.proto`，Cmd 分配在群命令区（0x0070~0x009B）之后取 **0x00A0~0x00A7**（ProtobufCodec 上限 255，余量充足）：

| Cmd | 方向 | 用途 |
|---|---|---|
| `CALL_INVITE_REQ/RESP` (0xA0/0xA1) | 客户端→logic | 发起通话（peerId, mediaType: audio/video） |
| `CALL_ACCEPT_REQ/RESP` (0xA2/0xA3) | 客户端→logic | 接听（callId） |
| `CALL_REJECT_REQ/RESP` (0xA4/0xA5) | 客户端→logic | 拒绝/取消/挂断（callId, reason） |
| `CALL_EVENT_PUSH` (0xA6) | logic→客户端 | 统一推送：呼入振铃、对方接受/拒绝/挂断/超时/忙 |
| `CALL_TOKEN_REQ/RESP` (0xA7) | 客户端→logic | （可选）断线重连/中途刷新时重新取 token |

注意两处已知技术债在新增命令时的联动：`MessageDispatcher.cmdToAddress` 是显式逐命令映射（新增必须登记，否则静默走 unknown cmd）；前端 SDK 的 Cmd 枚举是手写的（曾因漏加 GROUP_KICK_RESP 出过超时事故），本次两端必须同批提交。

### 3.3 通话状态机（1:1）

```
          ┌─────────┐ invite        ┌─────────┐
          │ idle    │──────────────→│ ringing │────────────┐
          └─────────┘               └────┬────┘    reject/  │ timeout(45s)/
              B 离线：写未接来电记录        │         cancel    │ B offline
              A cancel ↑                 │ accept      ↓     ↓
                        ←────────────────┘        ┌──────────────┐
                          双方各发 token            │ active       │
                          A/B 连 livekit 房间       │ (通话中)      │
                                                  └──────┬───────┘
                                   hangup(任一方)/断连/webhook 兜底
                                                         ↓
                                                  ended（落库：时长、结束原因）
```

- **振铃**：`CALL_INVITE` 校验好友关系（复用 `GroupRepository.isFriend`/im_friend）与目标在线态（SessionRouteTable）；推 `CALL_EVENT_PUSH{type=ringing}` 给 B。B 离线不立即失败——挂 45 秒超时器，到点写**未接来电**（见 3.5），A 收到 `timeout`。
- **忙线**：Redis `call:active:{userId}`（value=callId，TTL=通话时长上限）判定；busy 直接拒绝并推送 A。
- **接听**：logic 生成**两份** token（同一 room `call-{callId}`，identity 各为双方 userId），accept 响应带 B 的 token、accept 推送带 A 的 token——一次交互双方拿到全部入会材料，无二次往返。
- **挂断**：任一方 `CALL_REJECT{reason=hangup}` → 推对方 + 删房间（Twirp DeleteRoom；亦可不显式删，靠 `empty_timeout` 自动关闭）→ 通话记录落库，会话列表生成一条"通话时长 mm:ss"。
- **兜底三重保险**：① 客户端显式信令（主路径）；② logic 的 45s 振铃超时 + 2h 通话硬上限定时器；③ LiveKit webhook `participant_left`/`room_finished`（进程被杀、断网未发 hangup 时由它收尾）。webhook 接收端点挂在 `ApiVerticle`（POST `/api/livekit/webhook`），用 API secret 验签，content-type 记得放行 `application/webhook+json`。

### 3.4 安全要点（承接本次 review 的教训）

- **room 名不可枚举**：`call-{snowflake}` 单调可猜——这正是 P1-3 里媒体对象名踩过的坑。用 `call-{snowflake}-{32位随机hex}`（复用 `ObjectKeys.randomToken` 的思路）。
- token 最小授权：identity=userId、room 单间、TTL 10~15 分钟；不签 admin grant。
- webhook 必须验签；`/api/livekit/webhook` 与其他 HTTP API 一样按 P2-7 的口径对待（当前 HTTP API 无鉴权是登记在案的既有风险，通话上生产前应一并收口）。
- 呼叫权限：仅限双向好友（防骚扰/枚举），陌生人不允许 INVITE。

### 3.5 存储与离线体验

- 新表 `im_call`（id、caller、callee、media_type、state、started_at、answered_at、ended_at、end_reason），**不走 seqsvr**——通话记录不是收件人信箱消息，不污染现有 `seq > sinceSeq` 水位语义。
- 未接来电 / 通话记录进会话列表：Phase 1 先由客户端在收到 push 时本地生成预览；离线补齐（B 离线期间的未接来电）Phase 2 做——可用一条 `msgType=CALL` 的轻量记录写入会话（需前端把它渲染为系统样式而非普通气泡），或拉历史时联查 `im_call`，实现时二选一再定。

### 3.6 前端（pomelo-web）

- 依赖只加 `livekit-client`（核心包，框架无关，避开 React 19 peer 风险）。
- 新组件（全部自绘，风格对齐现有语音条）：
  - `CallOverlay`：全屏遮罩式浮层，覆盖主叫呼出中（头像+振铃动画）、被叫来电（接受/拒绝）、通话中（远端视频 `<video>`、本地小窗、静音/摄像头/挂断、时长计时、弱网提示）。
  - 复用点：录音按钮已有的设备权限申请经验；来电铃声（新增两个音频资源）；波形的 `prefers-reduced-motion` 兜底原则照搬。
- 注意浏览器约束：getUserMedia 需安全上下文（localhost 与 https 均可，现有 Vite TLS 代理开发法已满足）；远端音频 autoplay 需用户手势——"接听"那次点击即是手势，天然满足；iOS Safari 需实测（Spike 覆盖）。

### 3.7 部署（docker-compose 增一个服务）

```yaml
livekit:
  image: livekit/livekit-server:v1.8.x   # 版本固定，勿用 latest（review 部署项的老毛病）
  command: --config /etc/livekit/livekit.yaml
  ports:
    - "7880:7880"               # 信令 WS（生产收进反代）
    - "7881:7881/tcp"           # WebRTC TCP 回退
    - "3478:3478/udp"           # TURN/UDP
    - "50000-60000:50000-60000/udp"  # 媒体 UDP
  volumes: [./conf/livekit.yaml:/etc/livekit/livekit.yaml:ro]
  networks: [pomelo-net]
```

- 开发环境（同一 Docker 网桥/同一局域网）：UDP 直连基本无忧，浏览器走 `ws://localhost:7880`。
- API key/secret 走 env 注入（复用 jwt.env 的做法，勿入库）；livekit.yaml 放 `conf/` 并同步 gitignore 敏感项。
- 生产：`rtc.use_external_ip: true`、TURN/TLS 443、信令反代到主域 `wss://…/livekit`；这批属于 review 报告"部署收口"批次的自然延伸。

---

## 四、实施计划

### Phase 0 — Spike 验证（0.5~1 天，先跑通再动手）

1. compose 起 livekit-server，`livekit.yaml` 最小配置（devkey + UDP 范围）。
2. 用 LiveKit 官方 [meet.livekit.io](https://meet.livekit.io)（填自托管 URL+token）或自写临时页面双浏览器互打一通，验证：局域网两台设备互看、页面刷新重连、arm64 镜像拉取 ⚠、Safari 支持度 ⚠。
3. 手工签一个 JWT（用本项目 JWTAuth）确认能入会——验证 Plan A"不引 SDK"成立。

### Phase 1 — 后端（2~3 天）

1. `proto/call/call.proto` + protobuf:compile + 前端 proto.js 同步；`ProtobufCodec`/`CodecRegistryHolder` 注册 0xA0~0xA7；`cmdToAddress` 登记 `logic.call`。
2. `CallService`：状态机（idle/ringing/active/ended）+ Redis 忙键 + 45s/2h 定时器 + 好友校验；token 签发（JWTAuth 扩展 video grants）；Twirp-JSON 轻客户端（CreateRoom/DeleteRoom）。
3. `ApiVerticle` 加 webhook 端点（验签 + participant_left/room_finished 收尾）。
4. `im_call` 建表 + 记录落库。
5. 测试：状态机单测（超时/忙线/重复接听/双方挂断竞态）、token claims 断言、webhook 验签单测——沿用本仓库"状态机 + 故障注入"的测试风格。

### Phase 2 — 前端（3~5 天）

1. SDK Cmd 枚举 + 通话 store（zustand，状态机与后端对齐）。
2. `CallOverlay` 全套 UI（呼出/来电/通话中/结束）+ 铃声 + livekit-client 接入（track attach、mute、camera 切换、断线重连提示）。
3. 会话列表通话记录预览；接听/挂断的消息样式。

### Phase 3 — 联调加固（2~3 天）

1. 真机矩阵：Chrome↔Safari、桌面↔移动浏览器、弱网（限速）下的 TCP/TURN 回退。
2. 异常路径全演练：接听瞬间挂断、双方同时挂断、A 掉线 B 等到超时、webhook 兜底验证。
3. 生产 TURN/external IP 配置 + 信令反代；对 P2-7（HTTP API 无鉴权）做收口，否则 webhook 端点裸奔。

### Phase 4 — 增强（按迭代节奏，另行评估）

群组通话（信令扇出复用群推送，UI 变宫格）、屏幕共享（`setScreenShareEnabled` 一行）、通话录制（Egress）、多设备同振（依赖 P2-2 多设备口径先定）、React Native 端（官方 RN SDK）。

**合计 MVP（Phase 0~3）：约 8~12 个工作日。**

---

## 五、风险清单

| # | 风险 | 等级 | 缓解 |
|---|---|---|---|
| R1 | NAT/防火墙：跨网通话 UDP 不通且未配 TURN | 高（生产） | 7881/TCP + TURN 两级回退；上线前按 Phase 3 清单验证 |
| R2 | livekit-server SDK 依赖冲突（protobuf/jackson 版本） | 中 | Phase 1 采用 Plan A 不引 SDK，仅手签 JWT + Twirp-JSON |
| R3 | iOS Safari 权限/autoplay/全屏行为差异 | 中 | Spike 即纳入 Safari；接听手势覆盖 autoplay；真机矩阵测试 |
| R4 | 手写 Cmd 枚举漏加导致请求超时（已有前科） | 中 | 两端同批提交 + 集成测试覆盖 CALL 全命令回路 |
| R5 | webhook 未验签被伪造拆散通话 | 中 | WebhookReceiver 等价验签实现 + 单测 |
| R6 | 单会话模型：用户顶号瞬间通话状态错乱 | 低 | call 状态机以 userId 为准，顶号后新会话重新同步（拉 `call:active`） |
| R7 | LiveKit 版本升级破坏性变更 | 低 | 镜像版本固定，升级走变更流程 |

---

## 六、结论

- **接入可行且成本可控**：媒体面交给 LiveKit 后，Pomelo 侧增量集中在"一个状态机服务 + 一个 webhook 端点 + 一张表 + 一套前端浮层"，不触碰 seqsvr/消息/网关核心链路，架构风险隔离良好。
- **推荐路径**：自托管 livekit-server + 信令复用 IM 命令通道 + Plan A（不引 Java SDK，手签 token + Twirp-JSON）+ 前端裸用 `livekit-client`。
- **先做 Phase 0 Spike**（半天到一天），用最小配置验证双端互通与 Safari 行为后，再启动 Phase 1。
