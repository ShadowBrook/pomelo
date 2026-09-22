# Pomelo 待办清单

> 2026-09-22 盘点。完成一项就把 `- [ ]` 改 `- [x]` 并注明提交号。

## 一、部署/运维（影响线上，最优先）

- [x] 生产 OSS 请求收口：`POMELO_MEDIA_PUBLIC_ENDPOINT=https://oss.pomelo.host`（服务器 env 必须生效、
      登录响应 avatar 的 host 自检见部署文档 §6）；前端生产构建不做任何改写（dev-only /minio 代理），
      Caddy 主域 /minio 兜底路由已加（660b9da），正确配置下不参与
- [x] DB schema 自动收敛：`db/schema.sql` 全量幂等化 + compose 一次性服务 `db-migrate`
      （每次 `up -d` 执行，pomelo 等它成功才启动）。修复线上「通话服务暂不可用」——
      老数据卷缺 `im_call.participants`（42703）。手工迁移登记制随之取消（文档 §4.1/§4.3）
- [x] 修复 LiveKit webhook 兜底静默失效（两个独立缺陷）：
      ① `logic.call.webhook` 消费端泛型写成 `<String>` 而 ApiVerticle 发 JsonObject →
      每次投递抛 ClassCastException；② `verifyWebhook` 与 livekit-server v1.13.6 实际行为不符——
      Authorization 是**裸 token**（我们要求 `Bearer ` 前缀）、sha256 声明是**标准 base64**
      （我们按 hex 比对）。已用探针容器抓真实报文定位，并把它固化成 golden 用例
- [x] 端口暴露面收紧：8888/9001 宿主绑定参数化（`POMELO_API_BIND`/`POMELO_WS_BIND`，demo 形态绑
      127.0.0.1 只经 Caddy 反代出公网）；修正文档此前"已全绑回环"的误述（基线实为 0.0.0.0 全开）
- [x] TCP 网关接入改造（Caddy layer4）：自定义镜像 `pomelo/caddy-l4:2`（xcaddy + mholt/caddy-l4，
      builder 阶段 `GOPROXY=https://goproxy.cn`，fa468b4），SNI 校验 + ACME 证书终结 → 明文转发网关。
      原生客户端 `tcps://pomelo.host:9000` 拿到正规 Let's Encrypt 证书（不再依赖 mkcert 自签）；
      宿主 9000 让给 caddy（网关宿主绑定挪 `POMELO_TCP_HOST_PORT=8999`，避开 silo 的 9002）
      （f8c5e13 + 端口冲突修复 1ed9fe8/ebd1fa2/4f7c1f2）
- [x] Grafana 公网入口：`grafana.<域名>` 子域（DNS A 记录 + Caddy 站点）+ 关闭匿名只读强制登录，
      凭据经服务器 `.env` 注入（`GRAFANA_ADMIN_PASSWORD` 缺失时 compose 拒启）。
      密码真相源在 grafana-data 卷而非 .env，重置法见部署文档 §3（4e187d6）
- [x] 备案号上站 + 域名形态切换：实测生产 Login chunk 含 `beian.miit.gov.cn` 与备案号（构建期
      `VITE_ICP_BEIAN` 注入，不入库）；`https://pomelo.host` 由 Let's Encrypt 正常签发服务
- [ ] 媒体面端口放行（用户侧，腾讯云安全组）：**3478/udp、7881/tcp 已通；`30000-30100/udp` 仍不通**
      （未放行时通话「能接通但两端黑屏/无声」，信令走 443 所以看起来一切正常）。
      自检：`python3 scripts/probe-media-ports.py pomelo.host`——注意 UDP 探针对媒体段存在假阴性
      （LiveKit 未必对空闲端口回 STUN），放行后仍以双端真实通话为最终判据

## 二、多端与消息（2026-09-21~22 实施）

- [x] 多端登录策略：同端型互踢（CTRL_TYPE_KICK_OFFLINE 通知后断开，客户端停止重连回登录页）+
      跨端共存（web/android/ios/unknown 平台槽位）；推送按端型扇出、发送者自己的其他端一并回推
      （f7d2e4d + bd53881，含 `PushCodec` v3 平台字段与 `client_msg_id` 合并去重）
- [x] 群名修改（群主/管理员）+ INFO_UPDATED 全端型扇出多端回显：后端 `45658a7`（proto 0x9C/0x9D +
      `GroupMemberChangeNotify.INFO_UPDATED` 携带新群名）、web `2ad311d`（详情面板铅笔入口）、
      安卓 `55f3a76`（点标题改名）
- [x] 安卓 SDK：`tcps://` 通道改用复合信任锚（系统 CA + 捆绑开发证书）——此前只信捆绑自签证书，
      连生产正规证书握手失败，表现为一直"连接中"（fea52d5）
- [x] 安卓：重连补拉的群消息入库 + 水位自愈 + 事件接线顺序（先刷新群列表再补拉）——修复
      **离线期间群消息永久丢失**（水位被推进而消息被丢弃；真机隔离测试验证，f5a8eb1）

## 三、通话（livekit-calling-plan Phase 4，另行评估）

- [ ] 屏幕共享
- [ ] Egress 录制
- [ ] 独立通话记录页（im_call 表已建，未接查询接口；目前仅会话流系统消息）
- [ ] 多设备同振（多端登录口径已实施，依赖已解除，可排期）
- [ ] 测试遗留：双浏览器/双账号真实接听场景；异常矩阵（Phase 3 联调矩阵）

## 四、设计文档已定稿未实施

- [ ] `2026-08-20-scale-readiness-plan.md`：整体尚未实施
- [ ] `2026-09-17-seqsvr-optimization-plan.md`：仅第一档可观测性已实施，其余待排期
- [ ] `2026-09-03-llm-stream-message-design.md`：草案待评审
- [ ] `2026-09-08-reply-and-forward-message-design.md`：更新文档状态（核心链路已实施；
      设计中「reply_json 列 + 服务端反查覆盖」未落库，当前引用快照由客户端构建、服务端原样存储）
- [ ] `AGENTS.md` 设计文档列表更新（引用/转发已实施；补头像/签名功能说明）

## 五、本期实施（2026-09-20）

- [x] 设置菜单：修改密码（HTTP + JWT 校验）、关于我们、帮助中心（静态弹窗）
- [x] 个性签名：im_user.signature 列 + ProfileUpdateReq optional signature + 读侧出口 + web 编辑/展示
- [x] 群转让 / 群解散：协议（0x76~0x79）+ 服务端（群主校验/成员通知 OWNER_TRANSFERRED、DISSOLVED）+ web 接线
- [x] 输入框 @ 提及：输入 @ / 按钮触发成员下拉（过滤 + 键盘选择）→ 插入文本；
      气泡 @token 高亮
- [x] @ 提及元数据化（2026-09-20 增补）：群消息 MessageContent.ext 携带
      mentioned_user_ids，服务端 im_message_group.ext 列持久化并随拉取/通知回传；
      web 会话列表「[有人@我]」标记（清零未读消除）；安卓接收侧 + Room v4 迁移

## 六、UI 占位

- [x] 登录页服务条款（三端共用服务端文案：GET /api/legal/terms，含「测试项目/禁止
      非法用途/不提供生产保障」声明）+ 邮箱找回密码（vertx-mail-client；验证码存
      Redis + 冷却 + 错 5 次作废）（后端 e215dac / web fea889f / 安卓 d36700b）
- [x] 绑定邮箱：注册可选填 + 设置页绑定（web 「设置 → 绑定邮箱」/ 安卓「我的 → 绑定邮箱」）
- [x] web 群公告编辑（群主/管理员；UpdateGroupReq.description optional，留空=清空）
- [x] 安卓群设置页：群名/公告编辑、成员列表与移出、邀请入群、群主转让/解散
- [x] 安卓：个性签名编辑/展示（我的页）、修改密码界面
- [ ] 安卓裁剪页 Compose 自绘（当前为库自带 Activity 风格）

## 七A、服务端配置待办（用户侧）

- [ ] 生产启用找回密码：服务器 `cp conf/mail.env.example conf/mail.env && chmod 600`
      填 SMTP（授权码）后 `docker compose up -d --force-recreate pomelo`；
      未配置时接口返回 503，不影响其他功能（步骤见部署文档 §8.1）

## 七、杂项

- [ ] `docs/diagrams/` 是否入库（后端仓库，未跟踪）
- [ ] 安卓仓库分支命名统一（现 main，另两仓 feat/*）
- [ ] 安卓 release 签名与分发（当前仅本地 `installDebug` 安装包）
