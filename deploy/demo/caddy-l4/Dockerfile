# =====================================================================
# Caddy + layer4 模块自定义镜像（官方 caddy:2 不含 l4，无法代理原始 TCP）。
# 用途：TCP 网关 9000 经 Caddy 统一接入并终结 TLS（复用 HTTP 站点的 ACME 证书，
# 原生客户端拿到正规证书，无需信任自签），见 deploy/demo/Caddyfile 的 layer4 全局块。
# 构建由 docker-compose.demo.yml 的 build: 触发：docker compose build caddy
# 服务器不构建镜像时用 §9 镜像传输法（开发机 build 后 docker save/load）。
# =====================================================================
FROM caddy:2-builder AS builder
# Go 模块走国内代理：proxy.golang.org 国内不可达（与 docs §2 Maven 配腾讯镜像同因）
ENV GOPROXY=https://goproxy.cn,direct
RUN xcaddy build --with github.com/mholt/caddy-l4

FROM caddy:2
COPY --from=builder /usr/bin/caddy /usr/bin/caddy
