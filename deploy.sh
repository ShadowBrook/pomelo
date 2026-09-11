#!/usr/bin/env bash
set -euo pipefail

# 部署脚本：install 依赖 → Jib 构建镜像 → 清理 dangling → docker compose 启动
#
# 用法：
#   ./deploy.sh                          # 全量构建 5 个服务镜像
#   ./deploy.sh pomelo-logic-server      # 只重建指定服务（-am 自动包含上游依赖）
#   ./deploy.sh pomelo-logic-server pomelo-gateway
#
# 服务模块 → 镜像名映射（见各模块 pom 的 jib <to><image>）：
#   pomelo-logic-server                  → pomelo/pomelo
#   pomelo-gateway                       → pomelo/gateway
#   pomelo-seqsvr/pomelo-seqsvr-store    → pomelo/seqsvr-store
#   pomelo-seqsvr/pomelo-seqsvr-mediate  → pomelo/seqsvr-mediate
#   pomelo-seqsvr/pomelo-seqsvr-server   → pomelo/seqsvr-alloc

# CLI Maven 需要 JDK 17+，macOS 默认 java 可能是 8
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk}"

# 切到脚本所在目录（repo 根）
cd "$(dirname "$0")"

JIB_GOAL="com.google.cloud.tools:jib-maven-plugin:3.4.0:dockerBuild"

ALL_SERVICES=(
  "pomelo-logic-server"
  "pomelo-gateway"
  "pomelo-seqsvr/pomelo-seqsvr-store"
  "pomelo-seqsvr/pomelo-seqsvr-mediate"
  "pomelo-seqsvr/pomelo-seqsvr-server"
)

if [ $# -gt 0 ]; then
  SERVICES=("$@")
else
  SERVICES=("${ALL_SERVICES[@]}")
fi

PL=$(IFS=,; echo "${SERVICES[*]}")

echo "==> [0/4] 生成开发用 TLS 自签名证书（已存在则跳过）"
./scripts/gen-dev-cert.sh

# JWT 签名密钥：HS256 的密钥即签发权，仓库内置密钥公开可见，必须换成随机值。
# 生成一次后复用（换值会使已签发 token 全部失效），文件不入库。
JWT_ENV_FILE="conf/jwt.env"
if [ ! -f "$JWT_ENV_FILE" ]; then
  umask 077
  printf 'POMELO_JWT_SECRET=%s\n' "$(openssl rand -hex 32)" > "$JWT_ENV_FILE"
  echo "==> 已生成 JWT 密钥：$JWT_ENV_FILE（请勿提交到仓库）"
else
  echo "==> 复用已有 JWT 密钥：$JWT_ENV_FILE"
fi

echo "==> [1/4] install 依赖到本地 .m2（-am 自动包含上游依赖）"
./mvnw install -pl "$PL" -am -DskipTests -q

echo "==> [2/4] Jib 构建镜像"
for mod in "${SERVICES[@]}"; do
  echo "---- 构建 $mod"
  ./mvnw -pl "$mod" -DskipTests "$JIB_GOAL"
done

echo "==> [3/4] 清理 dangling 镜像"
docker image prune -f

echo "==> [4/4] 启动/更新容器"
docker compose up -d

echo ""
echo "==> 完成，容器状态："
docker compose ps
