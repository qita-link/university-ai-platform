#!/usr/bin/env bash
# =====================================================================
# 构建镜像并导出离线包（"打包 docker"）
#
#   bash scripts/docker-build.sh            只构建
#   bash scripts/docker-build.sh --save     构建 + 导出 dist/campus-ai-images.tar.gz
#
# 导出的包可以在没有外网、没装 MySQL 的机器上：
#   docker load -i campus-ai-images.tar.gz
#   docker compose --env-file .env.docker up -d      # 不用加 --build
# =====================================================================
set -euo pipefail

cd "$(dirname "$0")/.."

ENV_FILE=".env.docker"
[[ -f "$ENV_FILE" ]] || { echo "缺少 $ENV_FILE" >&2; exit 1; }

echo "[build] 构建后端与前端镜像…"
docker compose --env-file "$ENV_FILE" build

# 记下构建时间与提交号，便于回溯这份包是什么时候的
COMMIT=$(git rev-parse --short HEAD 2>/dev/null || echo "no-git")
STAMP=$(date +%Y%m%d-%H%M)
echo "$STAMP $COMMIT" > dist/.last-build

if [[ "${1:-}" == "--save" ]]; then
  mkdir -p dist
  OUT="dist/campus-ai-images-${STAMP}.tar"
  echo "[build] 导出镜像到 $OUT …"
  docker save -o "$OUT" \
    campus-ai/backend:0.1.0 \
    campus-ai/web:0.1.0 \
    mysql:8.0

  echo "[build] 压缩…"
  gzip -9 -f "$OUT"

  ( cd dist && sha256sum "campus-ai-images-${STAMP}.tar.gz" > "campus-ai-images-${STAMP}.tar.gz.sha256" )

  echo "[build] 完成："
  ls -lh "dist/campus-ai-images-${STAMP}.tar.gz"
  echo
  echo "目标机器上的用法："
  echo "  docker load -i campus-ai-images-${STAMP}.tar.gz"
  echo "  docker compose --env-file .env.docker up -d"
fi
