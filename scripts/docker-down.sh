#!/usr/bin/env bash
# =====================================================================
# 停止并清理容器（默认保留数据库卷）
#   bash scripts/docker-down.sh           只停容器，数据保留
#   bash scripts/docker-down.sh --purge   连数据库卷一起删（不可恢复）
# =====================================================================
set -euo pipefail

cd "$(dirname "$0")/.."

ENV_FILE=".env.docker"
ARGS=(--env-file "$ENV_FILE")

if [[ "${1:-}" == "--purge" ]]; then
  echo "[docker] 删除容器与数据卷（数据库内容会全部丢失）"
  docker compose "${ARGS[@]}" down -v
else
  docker compose "${ARGS[@]}" down
fi
