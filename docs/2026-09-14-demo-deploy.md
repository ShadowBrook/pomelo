# 演示部署 Runbook（腾讯云轻量 4C4G3M · pomelo.host / oss.pomelo.host）

> 目标：把 Pomelo（IM + 1:1 音视频通话）部署到公网服务器，供 5-6 人异地演示。
> 双域名 + Caddy 自动 HTTPS（Let's Encrypt），演示设备**零信任配置**（不用装根证书）。
> 首次配置约 30-60 分钟，后续重演示只需 `up -d`。

## 1. 拓扑

```
浏览器/手机
  │ https://pomelo.host               https://oss.pomelo.host
  ▼
Caddy (80/443, Let's Encrypt 自动签发续期)
  ├─ /                → 前端静态资源 (pomelo-web/dist)
  ├─ /api/*           → pomelo:8888 (HTTPS 自签，跳过校验)
  ├─ /ws              → pomelo-gateway:9001 (WSS)
  ├─ /lk/*            → livekit:7880 (剥前缀，LiveKit 信令)
  └─ oss 子域整站     → silo:9000 (MinIO S3，presigned URL 直连)
ICE 媒体直连（不经 Caddy）：7881/tcp + 3478/udp + 30000-30100/udp
livekit → https://pomelo.host/api/livekit/webhook（webhook 兜底，已启用）
```

## 2. 前置条件

| 项 | 要求 |
|---|---|
| 服务器 | 腾讯云轻量 4核4G3M，公网 IP `1.15.179.198` |
| DNS | `pomelo.host` 与 `oss.pomelo.host` 两条 A 记录 → `1.15.179.198`（生效后再启动 Caddy，否则 ACME 签发失败） |
| **备案** | **国内节点必须完成 ICP 备案，否则腾讯云会拦截 80/443 上的域名访问**。备案走腾讯云控制台（约 1-2 周）；没备案前可先用纯 IP 形态过渡，见 §10 |
| 防火墙 | 腾讯云控制台防火墙（默认拒绝）只放行：**80/tcp、443/tcp+udp、7881/tcp、3478/udp、30000-30100/udp**。80 是 ACME 校验和 HTTP 跳转必需。切勿放行 5432/6379/8888/9001/9002 |

> 基线 compose 已把数据库/Redis/API/网关等全部端口绑到 127.0.0.1，双重保险。

## 3. 服务器初始化

```bash
# Docker + compose 插件（腾讯内网源，参考腾讯云官方文档）
curl -fsSL https://get.docker.com | bash
systemctl enable --now docker

# Docker Hub 镜像加速（拉 postgres/redis/livekit/caddy 基础镜像用）：
# 腾讯云内网源 https://mirror.ccs.tencentyun.com，写入 /etc/docker/daemon.json 后 restart docker
```

## 4. 镜像分发（本地构建 → 服务器加载）

仓库 `.mvn/jvm.config` 指向本地代理，**服务器上不要执行 Maven 构建**，在开发机打包后传输：

```bash
# 开发机：构建 5 个业务镜像（deploy.sh 会生成 conf/jwt.env、conf/livekit.env 随机密钥）
./deploy.sh                 # 全量构建

# 开发机：导出 → 传输 → 服务器加载（约 500MB，一次性）
docker save pomelo/pomelo pomelo/gateway pomelo/seqsvr-store pomelo/seqsvr-mediate pomelo/seqsvr-alloc | gzip > pomelo-images.tgz
scp pomelo-images.tgz ubuntu@1.15.179.198:
# 服务器
docker load < pomelo-images.tgz
```

基础镜像（postgres/redis/caddy/livekit/pgsty-silo）在服务器上 `up -d` 时自动拉（配了镜像加速）。

## 5. 代码与前端产物

```bash
# 开发机：构建前端静态资源
cd pomelo-web && npm ci && npm run build   # 产物在 dist/

# 同步两个仓库到服务器（路径约定：服务器 ~/pomelo 与 ~/pomelo-web 并排）
rsync -av --exclude target --exclude node_modules --exclude .git ~/code/pomelo/ ubuntu@1.15.179.198:~/pomelo/
rsync -av ~/code/pomelo-web/dist/ ubuntu@1.15.179.198:~/pomelo-web/dist/
```

## 6. 配置与启动

```bash
cd ~/pomelo
cp .env.demo.example .env      # DEMO_DOMAIN=pomelo.host / DEMO_MEDIA_DOMAIN=oss.pomelo.host
# import.d 目录保持为空（域名形态走 Let's Encrypt；纯 IP 备选形态才需要 tls-local.caddy）

./deploy.sh                 # 生成 conf/jwt.env、conf/livekit.env（随机密钥，勿提交/勿泄露）
docker compose -f docker-compose.yml -f docker-compose.demo.yml up -d

# 验证
docker compose ps                                  # 全部 Up / healthy
docker logs pomelo-caddy 2>&1 | grep -i "certificate obtained"   # 两张证书签发成功
curl -s https://pomelo.host/api/health             # {"status":"ok"}
curl -sI https://oss.pomelo.host | head -1         # 200/403 均说明 MinIO 已可达
docker logs pomelo-livekit 2>&1 | grep -iE "nodeip|webhook"      # nodeIP=公网IP
```

站点相关全部经环境注入（`docker-compose.demo.yml` → ConfigHolder `POMELO_*` 覆盖），**换域名无需重打镜像**：

- `POMELO_LIVEKIT_PUBLIC_URL=wss://pomelo.host/lk`
- `POMELO_MEDIA_PUBLIC_ENDPOINT=https://oss.pomelo.host`
- webhook 兜底已在 `conf/livekit.demo.yaml` 启用（`https://pomelo.host/api/livekit/webhook`）：
  logic 进程被杀/断网导致客户端发不出 END 时，由房间事件触发服务端强制收尾。

## 7. 3M 带宽策略（重要）

前端已内置视频发布压制（`useCallStore` CAM_CAPTURE/CAM_PUBLISH）：摄像头采集与编码锁 **480p / 500kbps**，单路通话服务端约 1.2Mbps，两路并发视频可跑。

- 演示动线建议：文字/语音消息、图片（几 MB 走 3M 需几秒）→ 语音通话（~0.15Mbps/路，随便并发）→ 视频通话压轴单路演示。
- 客户端崩溃/断网：前端监听 LiveKit `Disconnected` 自动发 END；webhook 兜底覆盖 logic 侧异常。不会出现“忙线卡 2 小时”。
- 如视频需更高画质：升配带宽或改香港节点，放宽 `CAM_PUBLISH.maxBitrate` 即可。

## 8. 演示账号与数据

```bash
# 注册（HTTP 开放注册，演示前预建好；昵称缺省取 userName）
curl -s https://pomelo.host/api/user/register -H 'Content-Type: application/json' \
  -d '{"userName":"demo-a","nickname":"演示A","password":"Passw0rd!23"}'
```

好友关系在 Web UI 里操作：登录 → 通讯录 → 搜索用户名 → 申请 → 对方同意。历史消息/通话记录全量持久化，重演示前可用测试账号留一轮现场数据。

## 9. 安全注意（公网暴露面）

- 开放注册接口是公开的——演示结束后关停或改密；正式使用需加邀请码/管理端。
- Redis 未设密码（演示取舍），但 6379 未对公网开放（绑定回环 + 云防火墙）；长期运行建议加 `requirepass` 并同步各服务 redis 配置。
- `conf/jwt.env`、`conf/livekit.env` 是 0600 随机密钥，泄露等同接管签发权；MinIO accessKey/secret 不出服务端。
- Let's Encrypt 证书无秘密可言，`conf/tls`（自签/开发证书）不需要上服务器——但同步了也无害。

## 10. 备选形态：纯 IP / 局域网（备案前的过渡）

**备案未完成期间，国内节点对未备案域名的 80/443 访问会被运营商/云厂商拦截**（按 Host/SNI 识别，表现为时通时断的重置/超时，之后趋于稳定拦截），且 Let's Encrypt 校验同样被拦、证书签不下来。此期间请用纯 IP 访问（IP 直连不受备案拦截）：

1. 服务器上换 Caddyfile 为 IP 变体：`cp deploy/demo/Caddyfile.ip deploy/demo/Caddyfile`
2. 启用 mkcert 证书片段：`cp deploy/demo/import.d/tls-local.caddy.example deploy/demo/import.d/tls-local.caddy`
3. `.env` 改 `DEMO_DOMAIN=1.15.179.198`（DEMO_MEDIA_DOMAIN 保留不动，该形态不使用）
4. 确认 `conf/tls/dev-server.crt` SAN 含该 IP（Mac 上 `mkcert -cert-file conf/tls/dev-server.crt -key-file conf/tls/dev-server.key localhost pomelo pomelo-gateway 127.0.0.1 ::1 1.15.179.198`），并同步 conf/tls 到服务器
5. `docker compose -f docker-compose.yml -f docker-compose.demo.yml up -d caddy` 重建 Caddy；演示设备装一次 `pomelo-web/public/rootCA.pem`（iOS 描述文件 + 完全信任；Android 安装 CA 证书）

备案下来后反向切回：还原域名版 Caddyfile、清空 import.d、`.env` 恢复两个域名、重建 caddy。局域网演示同法（SAN 换成局域网 IP）。

