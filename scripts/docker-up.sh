#!/usr/bin/env bash
# =====================================================================
# 一键构建并启动（本机或服务器通用）
#   bash scripts/docker-up.sh          构建 + 启动
#   bash scripts/docker-up.sh --no-build   只启动已有镜像
# =====================================================================
set -euo pipefail

cd "$(dirname "$0")/.."

ENV_FILE=".env.docker"
if [[ ! -f "$ENV_FILE" ]]; then
  echo "[docker] 缺少 $ENV_FILE，正在从模板生成…"
  cp .env.docker.example "$ENV_FILE"
  echo "[docker] 请先填写 DB_PASSWORD / DB_ROOT_PASSWORD / APP_JWT_SECRET 再重跑本脚本" >&2
  exit 1
fi

# 如果没有初始化数据，提示但不阻断（空库会由 Flyway 建表）
if ! ls docker/mysql/initdb/*.sql >/dev/null 2>&1; then
  echo "[docker] 提示：docker/mysql/initdb/ 里没有 .sql，将使用空库 + Flyway 自动建表"
fi

echo "[docker] 构建镜像…"
if [[ "${1:-}" == "--no-build" ]]; then
  docker compose --env-file "$ENV_FILE" up -d
else
  docker compose --env-file "$ENV_FILE" up -d --build
fi

echo "[docker] 等待服务就绪…"
for i in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$(grep -E '^WEB_HOST_PORT=' "$ENV_FILE" | cut -d= -f2)/" || true)
  if [[ "$code" == "200" ]]; then
    port=$(grep -E '^WEB_HOST_PORT=' "$ENV_FILE" | cut -d= -f2)
    echo "[docker] 就绪：http://localhost:${port}"
    docker compose --env-file "$ENV_FILE" ps
    exit 0
  fi
  sleep 3
done

echo "[docker] 超时未就绪，查看日志：" >&2
docker compose --env-file "$ENV_FILE" logs --tail 50
exit 1
