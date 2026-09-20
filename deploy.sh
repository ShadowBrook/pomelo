#!/usr/bin/env bash
set -euo pipefail

# 部署脚本：install 依赖 → Jib 构建镜像 → 清理 dangling → docker compose 启动
#
# 用法：
#   ./deploy.sh                          # 全量构建 5 个服务镜像
#   ./deploy.sh pomelo-logic-server      # 只重建指定服务（-am 自动包含上游依赖）
#   ./deploy.sh pomelo-logic-server pomelo-gateway
#   SKIP_BUILD=1 ./deploy.sh（或 ./deploy.sh --skip-build）
#                                        # 跳过构建，直接用本地已有镜像 up -d——
#                                        # 服务器上没有构建环境时用（镜像按 runbook §4 从开发机传输）
#
# JDK：自动探测，探测顺序见 find_java_candidates（$JAVA_HOME → SDKMAN → Homebrew
# → /usr/libexec/java_home → /usr/lib/jvm → PATH），要求 17+。
# 注意 .mvn/jvm.config 内置了开发机本地代理，跨机器构建前先确认其适用性。
#
# 服务模块 → 镜像名映射（见各模块 pom 的 jib <to><image>）：
#   pomelo-logic-server                  → pomelo/pomelo
#   pomelo-gateway                       → pomelo/gateway
#   pomelo-seqsvr/pomelo-seqsvr-store    → pomelo/seqsvr-store
#   pomelo-seqsvr/pomelo-seqsvr-mediate  → pomelo/seqsvr-mediate
#   pomelo-seqsvr/pomelo-seqsvr-server   → pomelo/seqsvr-alloc

REQUIRED_JAVA_MAJOR=17

# 候选 JAVA_HOME 列表（每行一个，顺序即优先级）。
# SDKMAN 的 sdkman-init.sh 只在交互 shell 生效，非交互 SSH 下 JAVA_HOME 不会导出，
# 所以必须直接探测 ~/.sdkman/candidates/java 目录。
find_java_candidates() {
  if [ -n "${JAVA_HOME:-}" ]; then
    printf '%s\n' "$JAVA_HOME"
  fi
  if [ -e "$HOME/.sdkman/candidates/java/current" ]; then
    printf '%s\n' "$HOME/.sdkman/candidates/java/current"
  fi
  if [ -d "$HOME/.sdkman/candidates/java" ]; then
    ls -1dt "$HOME/.sdkman/candidates/java/"*/ 2>/dev/null || true
  fi
  if [ -d /opt/homebrew/opt/openjdk ]; then
    printf '%s\n' /opt/homebrew/opt/openjdk
  fi
  if [ -d /usr/local/opt/openjdk ]; then
    printf '%s\n' /usr/local/opt/openjdk
  fi
  if [ -x /usr/libexec/java_home ]; then
    /usr/libexec/java_home 2>/dev/null || true
  fi
  # Linux 包管理器（apt/yum）安装位置；命名含 temurin/zulu/corretto 等，全量枚举
  ls -1d /usr/lib/jvm/*/ 2>/dev/null || true
  # PATH 上的 java 反解（alternatives/jenv/asdf 等剩余场景）
  if command -v java >/dev/null 2>&1; then
    local rp
    rp="$(readlink -f "$(command -v java)" 2>/dev/null || true)"
    if [ -n "$rp" ]; then
      dirname "$(dirname "$rp")"
    fi
  fi
  return 0
}

# 兼容 "21.0.8" 与 "1.8.0_392" 两种版本串格式
java_major_version() {
  "$1/bin/java" -version 2>&1 | head -1 |
    sed -E 's/.*version "([0-9]+)(\.([0-9]+))?.*".*/\1 \3/' |
    awk '{ print ($1 == 1) ? $2 : $1 }'
}

# 输出第一个满足版本要求的 JAVA_HOME；找不到时向 stderr 给出可操作的提示并返回 1
pick_java_home() {
  local c major best="" best_major=0
  for c in $(find_java_candidates); do
    c="${c%/}"
    [ -x "$c/bin/java" ] || continue
    major="$(java_major_version "$c")" || continue
    case "$major" in '' | *[!0-9]*) continue ;; esac
    if [ "$major" -ge "$REQUIRED_JAVA_MAJOR" ]; then
      printf '%s\n' "$c"
      return 0
    fi
    if [ "$major" -gt "$best_major" ]; then
      best="$c"
      best_major="$major"
    fi
  done
  if [ -n "$best" ]; then
    echo "[deploy] 探测到的最高 JDK 为 ${best_major}（$best），本项目需要 ${REQUIRED_JAVA_MAJOR}+" >&2
  else
    echo "[deploy] 未找到任何可用的 JDK。已探测：\$JAVA_HOME、SDKMAN(~/.sdkman)、Homebrew、/usr/libexec/java_home、/usr/lib/jvm、PATH" >&2
  fi
  echo "[deploy] 安装示例：macOS 'brew install openjdk@21' ｜ SDKMAN 'sdk install java 21-tem' ｜ Ubuntu 'sudo apt install openjdk-21-jdk'" >&2
  echo "[deploy] 服务器上通常不需要构建：在开发机构建镜像并传输后，直接 SKIP_BUILD=1 ./deploy.sh" >&2
  return 1
}

# 切到脚本所在目录（repo 根）
cd "$(dirname "$0")"

JIB_GOAL="com.google.cloud.tools:jib-maven-plugin:3.4.4:dockerBuild"

# 目标 CPU 平台：默认本机架构（Apple Silicon Mac = arm64，本地 compose 用）。
# 服务器（x86_64）镜像必须显式交叉构建，否则 arm64 镜像在服务器上无法运行：
#   JIB_PLATFORMS=linux/amd64 ./deploy.sh ...
# 服务器架构用 `uname -m` 确认（x86_64→amd64，aarch64→arm64）。
JIB_PLATFORMS="${JIB_PLATFORMS:-}"
JIB_ARCH_ARGS=""
[ -n "$JIB_PLATFORMS" ] && JIB_ARCH_ARGS="-Djib.architecture=${JIB_PLATFORMS##*/}"
# 本机访问 Docker Hub / Maven 中央仓库需要代理时注入（仅构建进程用，不入仓库）：
#   JIB_PROXY=127.0.0.1:5780 ./deploy.sh ...
JIB_PROXY="${JIB_PROXY:-}"
JVM_PROXY_ARGS=""
if [ -n "$JIB_PROXY" ]; then
  JVM_PROXY_ARGS="-Dhttp.proxyHost=${JIB_PROXY%%:*} -Dhttp.proxyPort=${JIB_PROXY##*:} -Dhttps.proxyHost=${JIB_PROXY%%:*} -Dhttps.proxyPort=${JIB_PROXY##*:}"
fi

ALL_SERVICES=(
  "pomelo-logic-server"
  "pomelo-gateway"
  "pomelo-seqsvr/pomelo-seqsvr-store"
  "pomelo-seqsvr/pomelo-seqsvr-mediate"
  "pomelo-seqsvr/pomelo-seqsvr-server"
)

SKIP_BUILD="${SKIP_BUILD:-0}"
SERVICES=()
if [ $# -gt 0 ]; then
  for arg in "$@"; do
    if [ "$arg" = "--skip-build" ]; then
      SKIP_BUILD=1
    else
      SERVICES+=("$arg")
    fi
  done
fi
if [ ${#SERVICES[@]} -eq 0 ]; then
  SERVICES=("${ALL_SERVICES[@]}")
fi

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

# LiveKit API 密钥：livekit 容器用它验 token，logic 用同一 secret 签 token。
# LIVEKIT_KEYS 是 livekit-server 原生读取的键值对（"apiKey: secret"）；
# POMELO_LIVEKIT_* 供 logic 容器读取（同值），secret 与 JWT 同法随机生成。
LIVEKIT_ENV_FILE="conf/livekit.env"
if [ ! -f "$LIVEKIT_ENV_FILE" ]; then
  umask 077
  LIVEKIT_SECRET="$(openssl rand -hex 32)"
  {
    printf 'LIVEKIT_KEYS=devkey: %s\n' "$LIVEKIT_SECRET"
    printf 'POMELO_LIVEKIT_API_KEY=devkey\n'
    printf 'POMELO_LIVEKIT_SECRET=%s\n' "$LIVEKIT_SECRET"
  } > "$LIVEKIT_ENV_FILE"
  echo "==> 已生成 LiveKit API 密钥：$LIVEKIT_ENV_FILE（请勿提交到仓库）"
else
  echo "==> 复用已有 LiveKit 密钥：$LIVEKIT_ENV_FILE"
fi

if [ "$SKIP_BUILD" = "1" ]; then
  echo "==> [1/4][2/4] 跳过构建（SKIP_BUILD=1），直接使用本地已有镜像"
else
  JAVA_HOME="$(pick_java_home)" || exit 1
  export JAVA_HOME
  echo "==> 使用 JDK：$(basename "$JAVA_HOME")（$("$JAVA_HOME/bin/java" -version 2>&1 | head -1 | sed 's/^.*version //')）"

  PL=$(IFS=,; echo "${SERVICES[*]}")

  echo "==> [1/4] install 依赖到本地 .m2（-am 自动包含上游依赖）"
  ./mvnw install -pl "$PL" -am -DskipTests -q $JVM_PROXY_ARGS

  PLATFORM_TAG=""
  PROXY_TAG=""
  [ -n "$JIB_PLATFORMS" ] && PLATFORM_TAG="（平台 ${JIB_PLATFORMS}）"
  [ -n "$JIB_PROXY" ] && PROXY_TAG="（代理 ${JIB_PROXY}）"
  echo "==> [2/4] Jib 构建镜像$PLATFORM_TAG$PROXY_TAG"
  for mod in "${SERVICES[@]}"; do
    echo "---- 构建 $mod"
    local_attempt=0
    until ./mvnw -pl "$mod" -DskipTests "$JIB_GOAL" $JIB_ARCH_ARGS $JVM_PROXY_ARGS; do
      local_attempt=$((local_attempt + 1))
      [ "$local_attempt" -ge 3 ] && { echo "==== 构建 $mod 连续失败，终止"; exit 1; }
      echo "---- 构建中断（代理抖动），第 $local_attempt 次重试（jib 层缓存使重试有进度）"
    done
  done
fi

echo "==> [3/4] 清理 dangling 镜像"
docker image prune -f

echo "==> [4/4] 启动/更新容器"
docker compose up -d

echo ""
echo "==> 完成，容器状态："
docker compose ps
