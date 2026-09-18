# 音视频通话接入 — 任务拆解与实施记录

- **日期**：2026-09-11
- **分支**：`feat/webrtc-calling`（自 `refactor/remove-json-codec` 拉出）
- **背景**：调研见 [2026-09-11-livekit-calling-feasibility.md](./2026-09-11-livekit-calling-feasibility.md)。本文件是执行层拆解，随实施滚动更新状态。
- **范围口径**：MVP = 1:1 音频/视频通话（好友之间），自托管 livekit-server；群通话/录制/多设备同振不在本期。

---

## 任务清单

### Phase 0 — Spike 与基础设施（后端仓库）

- [x] P0-1 `conf/livekit.yaml` 最小配置：devkey（env 注入）、UDP 端口范围、信令 7880、`room.empty_timeout=60s`、webhook 指向 logic
- [x] P0-2 `docker-compose.yml` 增加 `livekit` 服务（镜像版本固定、端口 7880/7881tcp/3478udp/50000-60000udp）
- [x] P0-3 敏感项处理：API secret 走 env；`conf/livekit.env` 加入 .gitignore（与 jwt.env 同法，deploy.sh 生成）
- [ ] P0-4 双端互通验证（**人工步骤，需真机/多浏览器**）：起容器 → 临时 token → 两个浏览器互看视频。完成前 Phase 2 前端不启动

### Phase 1 — 后端信令与状态机

**协议层**

- [x] P1-1 `pomelo-common/src/main/proto/call/call.proto`：CallInviteReq/Resp、CallAcceptReq/Resp、CallEndReq/Resp（拒绝/取消/挂断统一）、CallEventPush、CallTokenReq/Resp
- [x] P1-2 `common.proto` Cmd 枚举新增 0x00A0~0x00A7
- [x] P1-3 `./mvnw protobuf:compile` 重新生成 Java 源（生成物入库，随本分支提交）
- [x] P1-4 编解码注册三处：`ProtobufCodec` 静态块（RESP/PUSH 的 parser）、`CodecRegistryHolder`（REQ 的 parser+DTO）、`MessageDispatcher.cmdToAddress`（`logic.call` 显式映射）

**服务层（pomelo-logic-server）**

- [x] P1-5 `LiveKitTokenService`：HS256 JWT，claims=`iss`(apiKey)/`sub`(userId)/`exp`/`nbf`/`video{roomJoin,room,canPublish,canSubscribe}`；TTL 15 分钟。用 Vert.x JWTAuth 实现，不引 livekit-server SDK（规避 protobuf 冲突）
- [x] P1-6 `LiveKitRoomClient`：Twirp JSON 模式封装 `RoomService/CreateRoom`、`RoomService/DeleteRoom`（Vert.x WebClient），配置 `livekit.host/apiKey/secret`
- [x] P1-7 `CallService` 状态机：
  - [x] INVITE：好友校验、忙线判定（Redis `call:active:{userId}`）、生成 callId（snowflake+随机后缀）、建房间、挂 45s 振铃超时器、推 `ringing` 给 B、写 `im_call`
  - [x] ACCEPT：校验接听者身份、清超时器、双方 token 一次下发（resp 带 B 的、push 带 A 的）、置 active、推 `accepted`
  - [x] END：cancel（主叫撤）/ reject（被叫拒）/ hangup（通话中挂）/ busy / timeout 五种结束原因统一走 `endCall`；删房间（尽力而为）、清 Redis 忙键、落库结束时间、推对方 `ended`
  - [x] 通话硬上限定时器（默认 2h），到点强制结束
- [x] P1-8 `LogicVerticle` 装配 + `logic.call` consumer
- [x] P1-9 webhook 接收端点（`ApiVerticle` POST `/api/livekit/webhook`）：验签（JWT 里 sha256(body)，用 API secret 验）、`participant_left`/`room_finished` 触发兜底收尾
- [x] P1-10 `db/schema.sql` 新增 `im_call` 表（IF NOT EXISTS，不动现有表）

**测试**

- [x] P1-11 `CallServiceTest` 状态机：振铃推送、接听双方拿到 token 且 room 一致、拒绝/超时/忙线结束原因、重复接听幂等、非好友拒绝、伪造接听者拒绝
- [x] P1-12 `LiveKitTokenServiceTest`：claims 逐项断言（iss/sub/exp/nbf/video grants/TTL）
- [x] P1-13 webhook 验签：合法签名通过、篡改 body 拒绝、错误 secret 拒绝
- [x] P1-14 全量 `./mvnw test` 回归（存量模块不劣化）

### Phase 2 — 前端（pomelo-web，独立分支/提交）

- [ ] P2-1 SDK：Cmd 枚举补 0xA0~0xA7、proto 生成物同步、通话 store（zustand）
- [ ] P2-2 `CallOverlay`：呼出中/来电/通话中/结束四态 UI + 铃声 + 权限申请
- [ ] P2-3 `livekit-client` 接入：connect、track attach、mute/camera、断线重连提示
- [ ] P2-4 会话列表通话记录预览 + 消息样式
- [ ] P2-5 前端测试 + 构建回归

### Phase 3 — 联调加固（合并前必须）

- [ ] P3-1 异常路径演练：接听瞬间挂断、双方同时挂断、A 掉线等超时、webhook 兜底、顶号后状态同步
- [ ] P3-2 Chrome↔Safari、桌面↔移动浏览器真机矩阵
- [ ] P3-3 弱网回退验证（TCP 7881 / TURN）
- [ ] P3-4 生产配置：`rtc.use_external_ip`、TURN/TLS、信令反代 wss、webhook 端点鉴权收口（关联 review P2-7）

### Phase 4 — 增强（另行评估，不在本期）

群组通话、屏幕共享、Egress 录制、通话记录页、多设备同振（依赖 P2-2 多设备口径）、RN 端。

---

## 实施记录

### 2026-09-12（Phase 2 完成并真链路验证）

- 前端 `feat/webrtc-calling` 分支两个提交：SDK 信令层（bundle 重生成 + CALL 命令/推送分发）与 UI 层（useCallStore 状态机 + CallOverlay + 头部拨打入口 + WebAudio 铃声）。
- **真链路冒烟（真实后端 + 真浏览器）**：登录态注入 → 聊天窗点击语音通话 → 后端日志 `通话振铃` → LiveKit 建房（房间名 snowflake+随机后缀）→ 45s 振铃超时（reason=5）→ 浮层自动收尾 → `im_call` 落库 → 会话预览"[语音通话] 未接听"。全链路无一处手工干预。
- 环境坑（已记录）：dev server 端口漂移（5174）与 `.env.development` 钉死的 `VITE_WS_URL=ws://localhost:5173/ws` 不一致导致 WS 假性中断，重启回 5173 解决。
- 待办：双浏览器双账号的"真实接听"场景（需要第二个人/第二个浏览器会话点接听），属 Phase 3 联调矩阵。

### 2026-09-11

- 拉出分支 `feat/webrtc-calling`。
- 完成 Phase 0 配置面（P0-1~3）与 Phase 1 全部后端项（P1-1~P1-14），详见各提交。
- 设计决策与调研报告的一致性：
  - **不引 livekit-server SDK**：token 用 Vert.x JWTAuth 手签（claims 与 Kotlin SDK `AccessToken.toJwt()` 产物一致），房间管理走 Twirp JSON（`application/json` POST `{host}/twirp/livekit.RoomService/{Method}`）。
  - **room 名防枚举**：`call-{snowflake}-{16位随机hex}`，随机段复用 `ObjectKeys` 的 SecureRandom 思路。
  - **接听即下发双方 token**：accept 响应带被叫 token、accept 推送带主叫 token，省一次 CALL_TOKEN 往返；CALL_TOKEN_REQ 保留用于断线重连。
  - **结束原因枚举**：`cancel/reject/hangup/busy/timeout/hangup_by_peer_drop`，webhook 兜底结束记 `peer_drop`。
- 已知留待项：
  - P0-4 双端互通必须人工验证（容器已提供，验证步骤在 P0-1 注释）。
  - `im_call` 只建表未接查询接口（通话记录页属 Phase 4）；会话列表预览走前端本地生成（P2-4）。
- 全量回归：common 48、logic 103（新增 23 例全绿；7 例失败仍全部为既有的 RedisIdGeneratorTest，与基线一致）、seqsvr 三模块 40、gateway 20 全绿。`conf/livekit.env` 已在本地生成并验证 compose 配置合法。
- 部署修复（2026-09-11）：媒体 UDP 段从 50000-60000 改为 **40000-40100**——macOS 临时端口范围（49152-65535）与之重叠，Docker Desktop 逐端口绑定映射时与系统 UDP 会话相撞（`bind: address already in use`），导致 livekit 容器起不来。另：`im_call` 建表脚本只在全新初始化的 `docker-entrypoint` 执行，**存量库需手工补表**（本次部署已补）。
- ICE 修复（2026-09-11）：双端互通首测失败（信令通、PC 15s 超时）。根因：LiveKit 向客户端广播容器自身 IP（172.18.x），Docker Desktop 上该网段从宿主机不可路由。修复：`rtc.node_ip.ipv4: "127.0.0.1"`（本机测试经 Docker 端口代理；跨设备改局域网 IP，生产用 `use_external_ip: true`）。修复后真浏览器验证通过：双端入会、互订音频 track。
- ICE 修复二（2026-09-12）：`rtc.node_ip.ipv4` 在 YAML 中不生效（容器内无该网卡，配置被忽略；此前 127.0.0.1 能通是 STUN srflx + NAT 打环的巧合）。改用 CLI 参数 `--node-ip 192.168.0.103` 强制宣告宿主机局域网 IP，启动日志确认生效。服务端候选日志（publisherCandidates）是定位此类问题的最短路径。
