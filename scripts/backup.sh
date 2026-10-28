#!/usr/bin/env bash
# =====================================================================
# 数据库备份（mysqldump + gzip）
#
# 用法：
#   ./scripts/backup.sh [输出文件]        # 默认 ./backup/campus_ai_<时间>.sql.gz
#
# 需要的环境变量（与部署时一致）：
#   DB_HOST(默认 127.0.0.1) DB_PORT(默认 3306) DB_NAME(默认 campus_ai)
#   DB_USERNAME(默认 campus) DB_PASSWORD(必填)
#
# 说明：
#   - 使用 --single-transaction 保证 InnoDB 一致性快照，不锁表；
#   - 不包含 CREATE DATABASE，恢复时先建库（见 scripts/init-db.sql）；
#   - 包含 flyway_schema_history，恢复后 Flyway 不会重复执行已应用的迁移。
# =====================================================================
set -euo pipefail

DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_NAME="${DB_NAME:-campus_ai}"
DB_USERNAME="${DB_USERNAME:-campus}"
DB_PASSWORD="${DB_PASSWORD:-}"

if [ -z "$DB_PASSWORD" ]; then
  echo "错误：未设置 DB_PASSWORD" >&2
  exit 1
fi

OUTPUT="${1:-./backup/${DB_NAME}_$(date +%Y%m%d_%H%M%S).sql.gz}"
mkdir -p "$(dirname "$OUTPUT")"

echo "备份 ${DB_HOST}:${DB_PORT}/${DB_NAME} -> ${OUTPUT}"
MYSQL_PWD="$DB_PASSWORD" mysqldump \
  --host="$DB_HOST" --port="$DB_PORT" --user="$DB_USERNAME" \
  --single-transaction --quick --routines --events \
  --default-character-set=utf8mb4 \
  --no-tablespaces \
  "$DB_NAME" | gzip -9 > "$OUTPUT"

# 简单校验：gzip 完整且包含建表语句
gzip -t "$OUTPUT"
gunzip -c "$OUTPUT" | grep -q "CREATE TABLE \`flyway_schema_history\`" \
  || { echo "警告：备份中未找到 flyway_schema_history，请确认库名" >&2; }

echo "完成：$(ls -lh "$OUTPUT" | awk '{print $5}')  ${OUTPUT}"
