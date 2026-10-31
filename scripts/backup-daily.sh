#!/usr/bin/env bash
# =====================================================================
# 校园 AI 平台 - 每日数据库备份
#
# 用法：campus-backup.sh
#   DB 账号从 /etc/campus-ai/env 读取（与 systemd 服务一致），也可用环境变量覆盖。
#   备份文件：$BACKUP_DIR/campus_ai-YYYYmmdd-HHMMSS.sql.gz
#   校验：gzip 完整性 + 至少 10 条 CREATE TABLE，任一不满足即删除本次文件并退出 1
#   保留：KEEP_DAYS 天（默认 7），更早的自动清理
#
# 建议 crontab（每天 03:10，避开整点任务）：
#   10 3 * * * /usr/local/bin/campus-backup.sh >> /var/log/campus-ai/backup.log 2>&1
# =====================================================================
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-/var/backups/campus-ai}"
KEEP_DAYS="${KEEP_DAYS:-7}"
DB_NAME="${DB_NAME:-campus_ai}"
ENV_FILE="${ENV_FILE:-/etc/campus-ai/env}"
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"

if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
fi

: "${DB_USERNAME:?未找到 DB_USERNAME（检查 $ENV_FILE）}"
: "${DB_PASSWORD:?未找到 DB_PASSWORD（检查 $ENV_FILE）}"

mkdir -p "$BACKUP_DIR"

ts="$(date +%Y%m%d-%H%M%S)"
out="$BACKUP_DIR/${DB_NAME}-${ts}.sql.gz"
tmp="$out.part"

cleanup() { rm -f "$tmp"; }
trap cleanup EXIT

mysqldump --single-transaction --quick --routines --triggers \
  --default-character-set=utf8mb4 \
  -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USERNAME" -p"$DB_PASSWORD" "$DB_NAME" 2>/dev/null \
  | gzip -9 > "$tmp"

# 校验 1：gzip 完整
if ! gzip -t "$tmp" 2>/dev/null; then
  echo "$(date '+%F %T') FAIL gzip 校验不通过，已丢弃" >&2
  exit 1
fi

# 校验 2：内容看起来是完整库（防止 mysqldump 半途失败却留下小文件）
tables="$(gzip -dc "$tmp" | grep -c '^CREATE TABLE' || true)"
if [ "${tables:-0}" -lt 10 ]; then
  echo "$(date '+%F %T') FAIL 只解析到 ${tables:-0} 张表，已丢弃" >&2
  exit 1
fi

mv "$tmp" "$out"
size="$(du -h "$out" | cut -f1)"
echo "$(date '+%F %T') OK $out ($size, ${tables} tables)"

# 清理过期备份
find "$BACKUP_DIR" -maxdepth 1 -name "${DB_NAME}-*.sql.gz" -type f -mtime +"$KEEP_DAYS" -delete
