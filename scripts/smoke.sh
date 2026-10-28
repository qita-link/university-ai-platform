#!/usr/bin/env bash
# =====================================================================
# 部署冒烟脚本（只依赖 curl + grep/sed，不需要 jq）
#
# 用法：
#   BASE_URL=http://127.0.0.1:8080 SMOKE_USERNAME=admin SMOKE_PASSWORD='***' ./scripts/smoke.sh
#
# 验证内容：
#   1. /actuator/health 为 UP
#   2. 登录拿到令牌
#   3. GET /ai/tools 返回工具目录（不需要模型）
#   4. 只读工具 searchForumPosts 立即执行并返回 SUCCEEDED
#   5. 写工具 createForumPost 只生成 PENDING_CONFIRM，随后拒绝，确认没有真的发帖
#
# 说明：脚本会用管理员账号临时建一个「冒烟测试分类」并在结束时删除，
#       不留下业务数据；写工具的“确认执行”路径由集成测试覆盖，避免污染生产库。
# =====================================================================
set -uo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
SMOKE_USERNAME="${SMOKE_USERNAME:-}"
SMOKE_PASSWORD="${SMOKE_PASSWORD:-}"
TOKEN=""
HTTP_CODE=""
HTTP_BODY=""
CREATED_CATEGORY=""
CATEGORY_ID=""
STAMP="$(date +%Y%m%d%H%M%S)"

cleanup() {
  # 失败或成功退出时都清掉本次创建的临时分类，避免留下业务数据
  if [ -n "$CREATED_CATEGORY" ] && [ -n "$CATEGORY_ID" ] && [ -n "$TOKEN" ]; then
    curl -sS -X DELETE "$BASE_URL/api/v1/forum/categories/$CATEGORY_ID" \
      -H "Authorization: Bearer $TOKEN" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

fail() {
  echo "FAIL: $*" >&2
  if [ -n "$HTTP_BODY" ]; then
    echo "响应：$HTTP_BODY" >&2
  fi
  exit 1
}

ok() {
  echo "  ok  $*"
}

# 从单行 JSON 中取字符串/数字字段（取第一个匹配）
json_str() { printf '%s' "$1" | grep -o "\"$2\":\"[^\"]*\"" | head -1 | sed "s/\"$2\":\"//; s/\"$//"; }
json_num() { printf '%s' "$1" | grep -o "\"$2\":[0-9]*" | head -1 | sed "s/\"$2\"://"; }

# request METHOD PATH [BODY]
request() {
  local method="$1" path="$2" body="${3:-}"
  local args=(-sS -X "$method" "$BASE_URL$path" -H 'Accept: application/json')
  if [ -n "$TOKEN" ]; then
    args+=(-H "Authorization: Bearer $TOKEN")
  fi
  if [ -n "$body" ]; then
    args+=(-H 'Content-Type: application/json' -d "$body")
  fi
  local resp
  resp=$(curl "${args[@]}" -w '\n%{http_code}') || fail "无法连接 $BASE_URL$path"
  HTTP_CODE=$(printf '%s' "$resp" | tail -1)
  HTTP_BODY=$(printf '%s' "$resp" | sed '$d')
}

expect_code() {
  [ "$HTTP_CODE" = "$1" ] || fail "$2（期望 HTTP $1，实际 $HTTP_CODE）"
}

echo "== 冒烟开始：$BASE_URL =="

# 1) 健康检查
echo "[1/5] 健康检查"
health=$(curl -fsS "$BASE_URL/actuator/health") || fail "健康检查不可访问"
printf '%s' "$health" | grep -q '"status":"UP"' || fail "健康检查状态不是 UP"
ok "/actuator/health = UP"

# 2) 登录
echo "[2/5] 登录"
[ -n "$SMOKE_USERNAME" ] && [ -n "$SMOKE_PASSWORD" ] || fail "请设置 SMOKE_USERNAME / SMOKE_PASSWORD"
request POST /api/v1/auth/login "{\"username\":\"$SMOKE_USERNAME\",\"password\":\"$SMOKE_PASSWORD\"}"
expect_code 200 "登录失败"
TOKEN=$(json_str "$HTTP_BODY" accessToken)
[ -n "$TOKEN" ] || fail "登录响应中没有 accessToken"
ok "登录成功（$SMOKE_USERNAME）"

# 3) 工具目录
echo "[3/5] 工具目录"
request GET /api/v1/ai/tools
expect_code 200 "读取工具目录失败（该账号可能没有 ai:tool:use）"
printf '%s' "$HTTP_BODY" | grep -q '"searchForumPosts"' || fail "工具目录缺少 searchForumPosts"
printf '%s' "$HTTP_BODY" | grep -q '"createForumPost"' || fail "工具目录缺少 createForumPost"
ok "工具目录可读"

# 4) 只读工具：调用即执行
echo "[4/5] 只读工具"
request POST /api/v1/ai/tool-calls '{"tool":"searchForumPosts","arguments":{"size":1}}'
expect_code 200 "调用只读工具失败"
printf '%s' "$HTTP_BODY" | grep -q '"status":"SUCCEEDED"' || fail "只读工具未返回 SUCCEEDED"
ok "searchForumPosts = SUCCEEDED"

# 5) 写工具：只生成待确认，拒绝后不产生数据
echo "[5/5] 写工具确认闸门"
request GET /api/v1/forum/categories
expect_code 200 "读取论坛分类失败"
CATEGORY_ID=$(json_num "$HTTP_BODY" id)
if [ -z "$CATEGORY_ID" ]; then
  request POST /api/v1/forum/categories "{\"name\":\"冒烟测试分类-$STAMP\",\"sortOrder\":0}"
  if [ "$HTTP_CODE" = "403" ]; then
    fail "需要管理员账号（forum:category:manage）才能完成写工具冒烟"
  fi
  expect_code 200 "创建冒烟分类失败"
  CATEGORY_ID=$(json_num "$HTTP_BODY" id)
  CREATED_CATEGORY=1
fi

request POST /api/v1/ai/tool-calls \
  "{\"tool\":\"createForumPost\",\"arguments\":{\"categoryId\":$CATEGORY_ID,\"title\":\"SMOKE-$STAMP\",\"content\":\"部署冒烟，不应真正发布\"}}"
expect_code 200 "调用写工具失败"
printf '%s' "$HTTP_BODY" | grep -q '"status":"PENDING_CONFIRM"' || fail "写工具没有返回 PENDING_CONFIRM"
CALL_ID=$(json_num "$HTTP_BODY" id)
[ -n "$CALL_ID" ] || fail "待确认记录缺少 id"

request POST "/api/v1/ai/tool-calls/$CALL_ID/reject" '{"reason":"部署冒烟"}'
expect_code 200 "拒绝工具调用失败"
printf '%s' "$HTTP_BODY" | grep -q '"status":"REJECTED"' || fail "拒绝后状态不是 REJECTED"
ok "createForumPost = PENDING_CONFIRM → REJECTED（未发帖）"

# 确认没有真的发帖
request GET "/api/v1/forum/posts?keyword=SMOKE-$STAMP"
expect_code 200 "校验帖子列表失败"
TOTAL=$(json_num "$HTTP_BODY" total)
[ "${TOTAL:-1}" = "0" ] || fail "拒绝执行后仍然产生了帖子（total=$TOTAL）"
ok "未产生任何帖子"

echo "== 冒烟通过 =="
