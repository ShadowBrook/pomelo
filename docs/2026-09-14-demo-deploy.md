# Pomelo 部署 Runbook（腾讯云轻量 · pomelo.host / oss.pomelo.host）

> Pomelo（IM + 音视频通话）公网部署与版本升级手册。双域名 + Caddy 自动 HTTPS，
> 演示设备零信任配置（不装根证书）。
>
> **部署方式（2026-09-21 起）**：代码直接拉到服务器，**在服务器上构建并部署**
>（后端 Maven/Jib、前端 Vite）。服务器 x86_64 本机构建天然就是 amd64，
> 无交叉构建问题；`.mvn/jvm.config` 已不含代理参数。
>
> **环境事实**：服务器 VM-0-4-ubuntu（Ubuntu 24.04，x86_64）；代码仓库为 GitHub
> 私有仓（pomelo、pomelo-web），服务器需配置 Deploy Key；安卓 APK 仍在开发机构建。
>
> **当前状态**：域名形态已上线运行（Caddy 双证书已签发、API 健康）。
> 最近一轮功能：个性签名、群主转让/解散、@提及（元数据 + [有人@我]）、头像裁剪、修改密码。
> 2026-09-21 修复两处线上缺陷：① 老数据卷缺 `im_call.participants` 导致通话报「通话服务暂不可用」
> ——改为 `db-migrate` 一次性服务自动收敛 schema（§4.1）；② LiveKit webhook 静默失效
> ——消费端 EventBus 类型不匹配 + 验签与 livekit-server 实际报文格式不符（§4.3 的 golden 用例）。
> 媒体面（UDP）连通性排障见 §4.4。

## 1. 拓扑（线上现状）

```
浏览器/手机
  │ https://pomelo.host               https://oss.pomelo.host
  ▼
Caddy (80/443, Let's Encrypt 自动签发续期)
  ├─ /                → 前端静态资源 (~/pomelo-web/dist，Caddy 挂载)
  ├─ /api/*           → pomelo:8888 (HTTPS 自签，跳过校验)
  ├─ /ws              → pomelo-gateway:9001 (WSS)
  ├─ /lk/*            → livekit:7880 (剥前缀，LiveKit 信令)
  ├─ /minio/*         → silo:9000 (兜底路由，正常配置下不参与，见 §5)
  └─ oss 子域整站     → silo:9000 (MinIO S3，presigned URL 直连)
ICE 媒体直连（不经 Caddy）：7881/tcp + 3478/udp + 30000-30100/udp
livekit → https://pomelo.host/api/livekit/webhook（webhook 兜底，已启用）
```

## 2. 服务器一次性环境（首次配置）

| 组件 | 安装 | 用途 |
|---|---|---|
| Docker + compose 插件 | `curl -fsSL https://get.docker.com \| bash && systemctl enable --now docker`；镜像加速写 `/etc/docker/daemon.json`（腾讯内网源 `https://mirror.ccs.tencentyun.com`）后重启 docker | 运行与镜像构建 |
| JDK 21 | `apt install -y openjdk-21-jdk-headless`（Ubuntu 24.04 自带 21） | 后端 Maven/Jib 构建 |
| Node.js 22 | `curl -fsSL https://deb.nodesource.com/setup_22.x \| bash - && apt install -y nodejs` | 前端 Vite 构建 |
| Git + Deploy Key | `ssh-keygen -t ed25519`，公钥分别添加到 GitHub 两个仓库的 **Settings → Deploy keys**（勾选只读即可） | 拉取私有仓库 |

防火墙（腾讯云控制台，默认拒绝）只放行：**80/tcp、443/tcp+udp、7881/tcp、3478/udp、
30000-30100/udp**。切勿放行 5432/6379/8888/9001/9002（基线 compose 已全部绑 127.0.0.1）。

Maven 依赖加速（可选，腾讯内网镜像）——`~/.m2/settings.xml`：

```xml
<settings>
  <mirrors>
    <mirror>
      <id>tencent</id>
      <mirrorOf>central</mirrorOf>
      <url>https://mirrors.cloud.tencent.com/nexus/repository/maven-public/</url>
    </mirror>
  </mirrors>
</settings>
```

Maven 发行版本体由 `./mvnw` 自动下载；内网拉取慢时可设 `MVNW_REPOURL` 指向腾讯镜像。

## 3. 首次部署（拉代码 → 构建 → 启动）

```bash
# 1) 克隆（两条线都已合入 main，服务器一律跟 main）
cd ~
git clone git@github.com:ShadowBrook/pomelo.git pomelo
git clone git@github.com:ShadowBrook/pomelo-web.git pomelo-web
# 路径约定：~/pomelo 与 ~/pomelo-web 并排（Caddy 挂载 ../pomelo-web/dist）
# ⚠️ 服务器跟 main，功能分支经 PR/merge 合入后才可见：git pull 后**必须确认拿到预期提交**
#    （`git log --oneline -1`；跟错分支时 pull 是静默成功的空操作，本地构建产物也不会变——
#     2026-09-21 备案号"部署了没生效"就是这个：clone 停在 main，而提交还在功能线上）

# 2) 后端 + 全量启动（首次约 15-30 分钟：Maven 发行版 + 全量依赖 + 基础镜像拉取）
cd ~/pomelo
cp .env.demo.example .env     # 含 COMPOSE_FILE（自动合并 demo overlay）
                              # DEMO_DOMAIN=pomelo.host / DEMO_MEDIA_DOMAIN=oss.pomelo.host
./deploy.sh                   # 构建 5 个业务镜像 + 生成 conf/jwt.env、conf/livekit.env（随机密钥）
                              # 末尾自动 docker compose up -d
# 3) 前端构建（dist 即 Caddy 挂载路径，即时生效）
cd ~/pomelo-web && npm ci
cp .env.example .env.local      # 站点文案/备案号等站点级配置写这里（*.local 不入库）
                                # 例：VITE_ICP_BEIAN=皖ICP备XXXXXXXXXX号（登录页底部展示并跳转工信部）
npm run build
grep -c "beian.miit.gov.cn" dist/assets/Login-*.js   # 备案号自检：≥1 才说明构建读到了变量

# 4) DB 迁移：无需手工执行——compose 的一次性服务 db-migrate 已在 up -d 时跑完
docker compose ps --all | grep db-migrate      # Exited (0) 即成功
```

> 对象存储 bucket：compose 已内置一次性 `minio-init` 服务自动创建 `pomelo-media`
>（复用 silo 镜像自带的 mc，`up -d` 时随栈启动并退出）。**缺 bucket 的症状**：
> 上传 PUT 返回 404（NoSuchBucket，读侧同样 404）。手动补建：
> `docker run --rm --network pomelo_pomelo-net --entrypoint /bin/sh pgsty/silo:RELEASE.2026-08-06T00-00-00Z -c 'mc alias set s http://silo:9000 pomelo-admin pomelo-admin-password >/dev/null && mc mb --ignore-existing s/pomelo-media'`

> 前端站点文案（备案号等）：写在 `~/pomelo-web/.env.local`（模板 `.env.example`，`*.local` 已 gitignore，
> 值不入库）。`VITE_*` 是**构建期**注入（Vite 内联进产物）——改值必须重新 `npm run build`，
> 它不是运行时变量；未配置时登录页只显示版权，不显示备案号。

> 前端构建报 `Cannot find module '.../lightningcss/node/index.mjs'`（或其它 native/optional
> 依赖缺失）：`node_modules` 是半成品（安装中断、换过 Node 版本、或从别处拷过依赖目录），
> npm 不会局部回滚，必须整目录重装：
>
> ```bash
> cd ~/pomelo-web && rm -rf node_modules && npm ci
> ls node_modules/lightningcss/node/index.mjs    # 自检：应存在
> ```
>
> `npm ci` 严格按 `package-lock.json` 装（含 `lightningcss-linux-x64-gnu` 原生包，Lock 里已固定）。
> 若 `npm ci` 自身失败（镜像源缺包/网络），把报错贴出来；应急可换官方源：
> `npm ci --registry=https://registry.npmjs.org`。构建失败不影响线上——Caddy 仍服务上一次的 `dist`。

## 4. 版本升级（日常主路径）

```bash
cd ~/pomelo && git pull          # 注意拉到最新（含 ConfigHolder 修复）
cd ~/pomelo-web && git pull

# 后端两个镜像重建 + 容器滚动更新（deploy.sh 末尾自动 up -d）
cd ~/pomelo && ./deploy.sh pomelo-logic-server pomelo-gateway

# 前端（原地构建，Caddy 立即提供新产物；Vite 自动读取 ~/pomelo-web/.env.local）
cd ~/pomelo-web && npm run build

# DB 迁移：随 up -d 自动执行（db-migrate），无需手工步骤；见 §4.1
```

### 4.1 DB schema 收敛（自动，无需手工迁移）

`db/schema.sql` 是唯一事实源，**全部语句都是幂等形态**（`CREATE TABLE/INDEX IF NOT EXISTS`、
`ALTER TABLE ... ADD COLUMN IF NOT EXISTS`），因此可以直接对既有库反复执行：

- 空数据卷：postgres 容器首启时经 `docker-entrypoint-initdb.d` 建表；
- 既有数据卷（老卷不会重跑 initdb）：compose 的一次性服务 **`db-migrate`** 在每次
  `docker compose up -d` 时执行 `psql -v ON_ERROR_STOP=1 -f db/schema.sql`，
  `pomelo` 侧 `depends_on: service_completed_successfully` ——迁移失败时业务不会带着旧表结构启动。

**因此新增列/建表的唯一动作是往 `db/schema.sql` 追加语句**（IF NOT EXISTS 形态），
不要在任何地方单独登记/手工执行迁移（历史上漏执行的正是 `im_call.participants`，
症状见 §4.3）。

手动兜底（deploy 后忘跑、或临时补列，幂等可重复）：

```bash
cd ~/pomelo && docker compose up -d db-migrate && docker inspect --format '{{.State.ExitCode}}' pomelo-db-migrate
```

历史迁移语句（已含在 schema.sql 内，仅作记录）：

| 日期 | 语句 |
|---|---|
| 2026-09-20 | `ALTER TABLE im_user ADD COLUMN IF NOT EXISTS signature VARCHAR(128) NOT NULL DEFAULT '';` |
| 2026-09-20 | `ALTER TABLE im_message_group ADD COLUMN IF NOT EXISTS ext TEXT;` |
| 2026-09-20 | `ALTER TABLE im_call ADD COLUMN IF NOT EXISTS participants TEXT NOT NULL DEFAULT '';` |

### 4.2 升级后验证

```bash
curl -s https://pomelo.host/api/health                          # {"status":"ok"}
docker inspect --format '{{.State.ExitCode}}' pomelo-db-migrate # 0
docker exec pomelo printenv | grep MEDIA_PUBLIC                 # https://oss.pomelo.host
curl -s https://pomelo.host/api/user/login -H 'Content-Type: application/json' \
  -d '{"userName":"...","password":"..."}' | grep -o '"avatar":"[^"]*"' | head -c 120
# avatar 必须以 https://oss.pomelo.host 开头（详见 §5）
# 通话链路的服务端错误都会落在这一行（建房/落库/缺列/密钥）：
docker compose logs pomelo | grep -E "建房|通话记录写入失败|webhook" | tail -20
# 媒体面连通性（从任意联网机器跑，不是服务器本机；黑屏/无声先查这个）
python3 scripts/probe-media-ports.py pomelo.host
```

安卓端 APK 仍在开发机构建后安装（服务器不配置 Android SDK）。

### 4.3 排障：通话「通话服务暂不可用」

该文案由 `CallService.invite` 的兜底分支返回，覆盖 **建房（LiveKit）/ 写 Redis 态 / 写 `im_call`**
三段中的任一失败，日志里必有 `建房/落库失败，回滚忙键 callId=...`，紧跟真实异常：

| 日志中的异常 | 原因 | 处理 |
|---|---|---|
| `column "participants" of relation "im_call" does not exist (42703)` | 老数据卷缺列（迁移没跑） | `docker compose up -d db-migrate` 后重拨（§4.1） |
| `CreateRoom ... 失败: HTTP 401` / `livekit.secret 缺失...拒绝签发` | `conf/livekit.env` 未注入或有损 | `docker compose up -d --force-recreate pomelo`（env_file 改动需重建容器） |
| `CreateRoom ... 失败: HTTP 404/502` | livekit 容器未起/不在同网 | `docker compose ps livekit`、`docker compose logs livekit \| tail -50` |

**注意**：LiveKit 房间在 `callRepo.insert` 之前就已创建，落库失败时房间会被删房逻辑收尾，
但**不会**自动重试——修完表结构重拨即可。

### 4.4 排障：通话能接通但两端黑屏 / 无声

信令走 `wss://pomelo.host/lk`（443，经 Caddy），媒体走**直连 UDP**，两者互不影响——
所以「振铃、接听、计时都正常，就是没有画面/声音」几乎总是媒体面被挡：

```bash
python3 scripts/probe-media-ports.py pomelo.host    # 任一项「不通」即命中
ss -lunp | grep -E '3478|3[0-9]{4}'                 # 服务器侧确认端口在听（docker-proxy/容器）
docker compose ps livekit                           # 端口映射是否在
```

- 云控制台**安全组**需放行 `3478/udp`（TURN）、`30000-30100/udp`（ICE 媒体）、`7881/tcp`（TCP 回退），
  模板见 §2。腾讯云默认拒绝，只在服务器上 `docker compose up -d` 是不够的。
- 客户端表现：ICE 永远打不通 → 发布（publish）不完成 → `cameraEnabled` 不翻转，
  界面上就是「摄像头没打开、画面全黑」，而且**不报错**——很容易误判成客户端 bug。
- 排掉后用同一脚本复测，五项全「通」再拨号。
- 跨网/移动网络下 UDP 易被丢，TURN(3478/udp) 兜底；TURN/TLS(443) 尚未配置（见 §6 与 livekit 调研文档 §2.3）。
- 端口放行后仍黑屏，则查 ICE 宣告地址：`docker compose logs livekit | head -5` 里的 `nodeIP`
  应为公网 IP（云主机网卡是内网 IP，靠 `use_external_ip` 探测）。若是 172.x，
  在 `docker-compose.demo.yml` 的 livekit `command` 上显式加 `--node-ip <公网IP>`
  （YAML 里的 `rtc.node_ip` 在容器内不生效，必须走命令行参数）。

## 5. 对象存储与 presigned URL 契约（上传/图片能否用的关键）

`POMELO_MEDIA_PUBLIC_ENDPOINT` **必须是 `https://oss.pomelo.host`**（demo overlay 已注入；
基线 config.yaml 默认 `http://localhost:9002` 仅开发机适用）。服务端用它签发 presigned URL，
浏览器直连 oss 子域（Caddy 整站反代 MinIO，Host 原样透传，SigV4 校验通过）。

**契约破坏的后果**：若下发 `http://` 形态 URL（如漏挂 demo overlay 回落到默认值）——
https 页面按混合内容规则禁直连，web 客户端会改写到主域 `/minio/...`，而主域 Caddy
无此路由 → 静态处理器对 PUT 返回 **405**（表现为改头像/发图失败）。

自检与兜底：

```bash
docker exec pomelo printenv | grep MEDIA_PUBLIC   # 期望 https://oss.pomelo.host
# 兜底：主域 /minio/* 兜底路由已入 Caddyfile 模板（header_up Host 回填签发 host），
# 服务器 rsync/git pull 仓库后 up -d caddy 生效——但正确配置下该路由不参与。
```

## 6. 3M 带宽策略（重要）

前端已内置视频发布压制（`useCallStore` CAM_CAPTURE/CAM_PUBLISH）：摄像头采集与编码锁 **480p / 500kbps**，单路通话服务端约 1.2Mbps，两路并发视频可跑。

- 演示动线建议：文字/语音消息、图片（几 MB 走 3M 需几秒）→ 语音通话（~0.15Mbps/路，随便并发）→ 视频通话压轴单路演示。
- 客户端崩溃/断网：前端监听 LiveKit `Disconnected` 自动发 END；webhook 兜底覆盖 logic 侧异常（09-21 前该兜底因
  EventBus 类型不匹配静默失效，现已修复——可用 `docker compose logs pomelo | grep webhook` 观察 `webhook 兜底结束通话`）。不会出现“忙线卡 2 小时”。
- 如视频需更高画质：升配带宽或改香港节点，放宽 `CAM_PUBLISH.maxBitrate` 即可。

## 7. 演示账号与可演示功能

```bash
# 注册（HTTP 开放注册，演示前预建好；昵称缺省取 userName）
curl -s https://pomelo.host/api/user/register -H 'Content-Type: application/json' \
  -d '{"userName":"demo-a","nickname":"演示A","password":"Passw0rd!23"}'
```

好友关系在 Web UI 里操作：登录 → 通讯录 → 搜索用户名 → 申请 → 对方同意。历史消息/通话记录全量持久化，重演示前可用测试账号留一轮现场数据。

**可演示功能（2026-09-20 起）**：头像裁剪上传（个人信息弹窗点头像）、个性签名（左上角点击编辑）、
修改密码（设置菜单）、群主转让/解散（群详情面板，仅群主）、@ 提及（群聊输入 @ 弹成员下拉，
被 @ 的人未读会话显示红色「[有人@我]」）、图片/视频/文件收发与引用/转发。

## 8. 安全注意（公网暴露面）

- 开放注册接口是公开的——演示结束后关停或改密；正式使用需加邀请码/管理端。
- Redis 未设密码（演示取舍），但 6379 未对公网开放（绑定回环 + 云防火墙）；长期运行建议加 `requirepass` 并同步各服务 redis 配置。
- `conf/jwt.env`、`conf/livekit.env` 是 0600 随机密钥，泄露等同接管签发权；MinIO accessKey/secret 不出服务端。
- 服务器上的构建密钥面：GitHub Deploy Key（只读）泄露影响限于拉代码；Maven settings 的镜像配置无秘密。

## 9. 备选：开发机构建 + 镜像传输（服务器不构建时）

服务器不配置 JDK/Node 时，回退为开发机构建、镜像与产物传输：

```bash
# 开发机（Apple Silicon → 服务器 x86_64 必须交叉构建 amd64）：
JIB_PROXY=127.0.0.1:5780 JIB_PLATFORMS=linux/amd64 ./deploy.sh pomelo-logic-server pomelo-gateway
docker save pomelo/pomelo pomelo/gateway | gzip > pomelo-update.tgz
scp pomelo-update.tgz ubuntu@1.15.179.198:
# 服务器
docker load < pomelo-update.tgz
cd ~/pomelo && docker compose -f docker-compose.yml -f docker-compose.demo.yml up -d pomelo pomelo-gateway
# 前端：开发机 npm run build 后 rsync dist/ 到 ~/pomelo-web/dist/
```

- `JIB_PROXY`：本机访问 Docker Hub 需代理时注入（仅构建进程，不入仓库）
- 为服务器构建后，本地 `:latest` 被 amd64 占用，回本地开发重跑默认构建恢复 arm64
- 不要绕过 deploy.sh 裸跑 `mvnw jib:dockerBuild`：不固定版本会解析到新版 jib（平台判定行为不同）

## 10. 备选形态：纯 IP / 局域网（备案前的过渡）

**备案未完成期间，国内节点对未备案域名的 80/443 访问会被运营商/云厂商拦截**（按 Host/SNI 识别，表现为时通时断的重置/超时，之后趋于稳定拦截），且 Let's Encrypt 校验同样被拦、证书签不下来。此期间请用纯 IP 访问（IP 直连不受备案拦截）：

1. 服务器上换 Caddyfile 为 IP 变体：`cp deploy/demo/Caddyfile.ip deploy/demo/Caddyfile`
2. 启用 mkcert 证书片段：`cp deploy/demo/import.d/tls-local.caddy.example deploy/demo/import.d/tls-local.caddy`
3. `.env` 改 `DEMO_DOMAIN=1.15.179.198`（DEMO_MEDIA_DOMAIN 保留不动，该形态不使用）
4. 确认 `conf/tls/dev-server.crt` SAN 含该 IP（Mac 上 `mkcert -cert-file conf/tls/dev-server.crt -key-file conf/tls/dev-server.key localhost pomelo pomelo-gateway 127.0.0.1 ::1 1.15.179.198`），并同步 conf/tls 到服务器
5. `docker compose -f docker-compose.yml -f docker-compose.demo.yml up -d caddy` 重建 Caddy；演示设备装一次 `pomelo-web/public/rootCA.pem`（iOS 描述文件 + 完全信任；Android 安装 CA 证书）

> **IP 形态功能降级**：对象存储走 `oss` 子域的 https presigned URL，纯 IP 形态下该子域不可用，
> 图片/视频/文件/头像等媒体功能无法使用（仅文字与音视频通话可用）。此形态仅为备案前过渡。

备案下来后反向切回：还原域名版 Caddyfile、清空 import.d、`.env` 恢复两个域名、重建 caddy。局域网演示同法（SAN 换成局域网 IP）。
