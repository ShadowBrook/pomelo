#!/usr/bin/env bash
# 生成开发用自签名证书（仅用于本地/内网联调，生产请替换为受信 CA 签发证书）。
# 产物：conf/tls/server.crt + conf/tls/server.key
set -euo pipefail

DIR="$(cd "$(dirname "$0")/.." && pwd)/conf/tls"
CRT="$DIR/server.crt"
KEY="$DIR/server.key"

if [ -f "$CRT" ] && [ -f "$KEY" ]; then
  echo "[gen-dev-cert] 证书已存在，跳过：$CRT"
  exit 0
fi

mkdir -p "$DIR"
openssl req -x509 -newkey rsa:2048 -nodes \
  -keyout "$KEY" -out "$CRT" -days 825 \
  -subj "/C=CN/O=Pomelo Dev/CN=pomelo" \
  -addext "subjectAltName=DNS:localhost,DNS:pomelo,DNS:pomelo-gateway,IP:127.0.0.1" \
  2>/dev/null

chmod 600 "$KEY"
echo "[gen-dev-cert] 已生成自签名证书：$CRT"
