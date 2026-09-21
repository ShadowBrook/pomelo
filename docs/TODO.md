# Pomelo 待办清单

> 2026-09-20 盘点。完成一项就把 `- [ ]` 改 `- [x]` 并注明提交号。

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
- [ ] 媒体面端口放行（用户侧，腾讯云安全组）：`3478/udp`、`30000-30100/udp`、`7881/tcp`；
      未放行时通话「能接通但两端黑屏/无声」（信令走 443 所以看起来一切正常）。
      自检：`python3 scripts/probe-media-ports.py pomelo.host`（当前线上五项全不通）
- [ ] 备案通过后 IP → 域名切换（用户侧）
- [ ] 备案号上站（用户侧）：服务器 `~/pomelo-web/.env.local` 写 `VITE_ICP_BEIAN=<备案号>`
      后重新 `npm run build`（构建期注入，不入库；未配置则登录页不显示该行）

## 二、通话（livekit-calling-plan Phase 4，另行评估）

- [ ] 屏幕共享
- [ ] Egress 录制
- [ ] 独立通话记录页（im_call 表已建，未接查询接口；目前仅会话流系统消息）
- [ ] 多设备同振（依赖多设备登录口径 P2-2）
- [ ] 测试遗留：双浏览器/双账号真实接听场景；异常矩阵（Phase 3 联调矩阵）

## 三、设计文档已定稿未实施

- [ ] `2026-08-20-scale-readiness-plan.md`：整体尚未实施
- [ ] `2026-09-17-seqsvr-optimization-plan.md`：仅第一档可观测性已实施，其余待排期
- [ ] `2026-09-03-llm-stream-message-design.md`：草案待评审
- [ ] `2026-09-08-reply-and-forward-message-design.md`：更新文档状态（核心链路已实施；
      设计中「reply_json 列 + 服务端反查覆盖」未落库，当前引用快照由客户端构建、服务端原样存储）
- [ ] `AGENTS.md` 设计文档列表更新（引用/转发已实施；补头像/签名功能说明）

## 四、本期实施（2026-09-20）

- [x] 设置菜单：修改密码（HTTP + JWT 校验）、关于我们、帮助中心（静态弹窗）
- [x] 个性签名：im_user.signature 列 + ProfileUpdateReq optional signature + 读侧出口 + web 编辑/展示
- [x] 群转让 / 群解散：协议（0x76~0x79）+ 服务端（群主校验/成员通知 OWNER_TRANSFERRED、DISSOLVED）+ web 接线
- [x] 输入框 @ 提及：输入 @ / 按钮触发成员下拉（过滤 + 键盘选择）→ 插入文本；
      气泡 @token 高亮
- [x] @ 提及元数据化（2026-09-20 增补）：群消息 MessageContent.ext 携带
      mentioned_user_ids，服务端 im_message_group.ext 列持久化并随拉取/通知回传；
      web 会话列表「[有人@我]」标记（清零未读消除）；安卓接收侧 + Room v4 迁移

## 五、UI 占位（功能开发中，未排期）

- [ ] 登录页：服务条款、发送邮件（找回密码）
- [ ] @ 提及元数据化：content 携带 mentioned_user_ids，气泡精确高亮 + 「有人@我」提示
- [ ] 安卓：个性签名编辑/展示、修改密码界面、群转让/解散界面
- [ ] 安卓裁剪页 Compose 自绘（当前为库自带 Activity 风格）

## 六、杂项

- [ ] `docs/diagrams/` 是否入库（后端仓库，未跟踪）
- [ ] 安卓仓库分支命名统一（现 main，另两仓 feat/*）
