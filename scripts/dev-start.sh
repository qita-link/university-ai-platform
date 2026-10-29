#!/usr/bin/env bash
# 本地一键启动：MySQL(3306) -> 后端(8080) -> 前端(5173)
# 数据库用项目内数据目录 .localrun/mysql-data，不使用系统 MySQL80 服务。
# 用法：bash scripts/dev-start.sh
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL="$ROOT/.localrun"
MYSQLD="D:/mysql/bin/mysqld.exe"
JAVA="$LOCAL/jdk-17.0.20.1+1/bin/java.exe"
JAR="$ROOT/target/campus-ai-platform-0.1.0-SNAPSHOT.jar"

port_up() { netstat -ano 2>/dev/null | grep -qE ":$1[[:space:]]+.*LISTENING"; }

wait_port() { # $1=port $2=秒
  for _ in $(seq 1 "$2"); do port_up "$1" && return 0; sleep 1; done
  return 1
}

if port_up 3306; then
  echo "[skip] 3306 已在监听"
else
  echo "[start] MySQL 3306 (datadir=$LOCAL/mysql-data)"
  "$MYSQLD" --defaults-file="$LOCAL/mysql-local.ini" --console >"$LOCAL/mysqld-run.out" 2>&1 &
  wait_port 3306 40 || { echo "[fail] MySQL 未起来，见 $LOCAL/mysqld-local.err"; exit 1; }
fi

if port_up 8080; then
  echo "[skip] 8080 已在监听"
else
  echo "[start] 后端 8080"
  export SERVER_PORT=8080   # 防止外部环境注入其他端口
  ( cd "$ROOT" && "$JAVA" -jar "$JAR" >"$LOCAL/app-run.log" 2>&1 & )
  if ! wait_port 8080 90; then
    echo "[fail] 后端未起来，见 $LOCAL/app-run.log 与 logs/campus-ai-platform.log"
    exit 1
  fi
fi

if port_up 5173; then
  echo "[skip] 5173 已在监听"
else
  echo "[start] 前端 5173"
  ( cd "$ROOT/frontend" && npm run dev >"$LOCAL/vite-run.log" 2>&1 & )
  wait_port 5173 60 || echo "[warn] 前端未就绪，见 $LOCAL/vite-run.log"
fi

echo
echo "前端  http://localhost:5173"
echo "后端  http://localhost:8080/actuator/health"
echo "接口文档 http://localhost:8080/swagger-ui.html"
