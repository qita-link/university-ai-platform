# 13 Docker 部署

> 一条命令起全套：MySQL 8 + Spring Boot 后端（含 OR-Tools 求解器）+ Nginx 前端。
> 不想装 JDK / Maven / Node / MySQL 也能跑，宿主机只需要 Docker。

## 1. 结构

```
                    ┌──────────────────────────────┐
   浏览器 :8088 ───▶│ web  (nginx:1.27-alpine)      │
                    │  静态资源 + SPA 回退           │
                    │  /api/* → app:8080            │
                    └──────────────┬───────────────┘
                                   │ 仅容器内网
                    ┌──────────────▼───────────────┐
                    │ app  (temurin 17-jre-jammy)   │
                    │  Spring Boot + OR-Tools       │
                    └──────────────┬───────────────┘
                                   │
                    ┌──────────────▼───────────────┐
                    │ db   (mysql:8.0)              │
                    │  utf8mb4_0900_ai_ci + ngram   │
                    └──────────────────────────────┘
```

对外只有 `web` 一个入口，`app` 和 `db` 都只在 compose 内网里，不映射到公网。

## 2. 前置要求

| 项 | 要求 |
|---|---|
| Docker | 20.10+，带 `docker compose`（v2 插件） |
| 内存 | 建议 ≥ 4 GB（后端 1 GB 堆 + MySQL 256 MB 缓冲池 + 构建峰值） |
| 磁盘 | ≥ 5 GB（镜像约 1.5 GB，离线包另算） |
| 架构 | 镜像按 amd64 构建；在 arm64 机器上构建需加 `--platform linux/amd64` |

检查：

```bash
docker --version
docker compose version
docker info --format '{{.ServerVersion}} {{.Architecture}}'
```

## 3. 快速开始

```bash
cd /opt/campus-ai          # 或你的项目目录

# 1) 准备变量（已内置一份可直接用的 .env.docker；换环境请改密码）
cp .env.docker.example .env.docker
vi .env.docker             # DB_PASSWORD / DB_ROOT_PASSWORD / APP_JWT_SECRET

# 2) 构建并启动
bash scripts/docker-up.sh
# 等价于：docker compose --env-file .env.docker up -d --build

# 3) 看状态
docker compose --env-file .env.docker ps
```

打开 `http://<服务器IP>:8088`。

首次启动顺序是：`db` 初始化（导入 `docker/mysql/initdb/` 里的 SQL）→ 健康检查通过 → `app` 启动跑 Flyway 校验 → `web` 起来。**第一次大约 1–3 分钟**，`app` 的 healthcheck 有 90 秒 `start-period`，别急着判失败。

## 4. 数据初始化

`docker/mysql/initdb/` 里的 `.sql` 只会在**数据卷为空**时执行一次，按文件名排序。

仓库里已经放了一份本机导出的数据（通用版，不含 `CREATE DATABASE`，所以库名跟着 `DB_NAME` 走）：

```
docker/mysql/initdb/10-campus_ai.sql
```

换成自己的数据：

```bash
rm docker/mysql/initdb/*.sql
cp /path/to/your-dump.sql docker/mysql/initdb/10-campus_ai.sql
docker compose --env-file .env.docker down -v     # 清掉旧数据卷才会重跑初始化
bash scripts/docker-up.sh
```

**不想要任何初始数据**（要一个干净库，让 Flyway 自己建表）：

```bash
rm docker/mysql/initdb/*.sql
bash scripts/docker-up.sh
```

空库时没有账号可登录，用 `.env.docker` 里的 `APP_BOOTSTRAP_ADMIN_USERNAME` / `APP_BOOTSTRAP_ADMIN_PASSWORD` 登录——这两个变量只在库里没有可用管理员时生效，首次登录后建议把密码那行删掉。

> 导出的 SQL 如果是「自包含版」（含 `CREATE DATABASE` / `USE`），导入时会无视 `DB_NAME` 固定写进 `campus_ai`。要么把 `DB_NAME` 保持为 `campus_ai`，要么用 `--no-create-db` 重新导一份。

## 5. 常用运维

```bash
ENV=.env.docker

docker compose --env-file $ENV ps                 # 状态
docker compose --env-file $ENV logs -f app        # 后端日志
docker compose --env-file $ENV logs -f db         # 数据库日志
docker compose --env-file $ENV restart app        # 重启后端
docker compose --env-file $ENV up -d --build app  # 只重建后端

# 进容器排查
docker exec -it campus-app sh
docker exec -it campus-db mysql -uroot -p campus_ai -e 'SELECT COUNT(*) FROM sys_user;'

# 数据库手工备份（导到宿主机）
docker exec campus-db mysqldump -uroot -p"$DB_ROOT_PASSWORD" \
  --single-transaction --routines --triggers --no-tablespaces \
  --default-character-set=utf8mb4 campus_ai | gzip > backup-$(date +%F).sql.gz

# 停止（保留数据）
bash scripts/docker-down.sh
# 停止并删除数据库（不可恢复）
bash scripts/docker-down.sh --purge
```

## 6. 离线打包与分发

在能联网的机器上构建并导出：

```bash
bash scripts/docker-build.sh --save
# 产物：dist/campus-ai-images-<时间戳>.tar.gz  + .sha256
```

目标机器（无外网、没装 MySQL）上：

```bash
sha256sum -c campus-ai-images-*.tar.gz.sha256
docker load -i campus-ai-images-*.tar.gz

# 需要 compose 文件、.env.docker、docker/ 目录一起拷过去
docker compose --env-file .env.docker up -d     # 注意：不要加 --build
```

包里含三个镜像：`campus-ai/backend:0.1.0`、`campus-ai/web:0.1.0`、`mysql:8.0`。

## 7. 上生产要改的几处

| 项 | 动作 |
|---|---|
| 密码 | `.env.docker` 里三个密钥全部换成新的；`APP_JWT_SECRET` 换掉后所有人重新登录 |
| 端口 | `WEB_HOST_PORT=80` 或直接在前面再放一层宿主机的 Nginx/Caddy 做 HTTPS |
| HTTPS | 推荐宿主机 Nginx + certbot 反代 `127.0.0.1:8088`；容器内不做证书 |
| 上传大小 | 前端 `client_max_body_size 20m`（`docker/web/nginx.conf`）与后端 `UPLOAD_MAX_REQUEST_SIZE` 要一起改 |
| 备份 | 见上一节的 mysqldump，配 cron；**数据在 `campus-ai-db-data` 卷里**，不要只备份 compose 文件 |
| 日志 | `campus-ai-app-logs` 卷；容器日志用 `docker compose logs`，可配 `logging.options.max-size` |
| AI | 默认 `AI_CHAT_PROVIDER=none`；要开就填 `AI_CHAT_PROVIDER=openai` + `AI_API_KEY` + `AI_BASE_URL` |
| 时间 | `TZ=Asia/Shanghai`、MySQL `default-time-zone='+08:00'`、应用 `APP_TIME_ZONE` 三处已对齐，改就一起改 |

### 反向代理后面拿不到真实 IP

`app` 已设 `SERVER_FORWARD_HEADERS_STRATEGY=native`，会读 `X-Forwarded-For`。如果再加一层宿主机 Nginx，记得透传：

```nginx
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
proxy_set_header X-Forwarded-Proto $scheme;
```

## 8. 排障

| 现象 | 原因 / 处理 |
|---|---|
| `app` 一直重启，日志 `Communications link failure` | `db` 还没就绪。compose 已用 `service_healthy` 等，若仍失败看 `docker compose logs db` |
| `db` 起不来，日志 `unknown variable 'ngram_token_size'` | 用的不是 MySQL 8 官方镜像（如 MariaDB）。换回 `mysql:8.0` |
| 建表失败 `Unknown collation: utf8mb4_0900_ai_ci` | 同上，或镜像被换成了 5.7 |
| 中文搜不到 | `ngram_token_size` 不是 2；改 `docker/mysql/conf.d/zz-campus.cnf` 后 **必须清卷重导**（`down -v`），索引不会自动重建 |
| 页面 502 | `app` 没起来。`docker compose logs app`，多半是 `APP_JWT_SECRET` 为空导致启动失败 |
| 上传报 413 | `docker/web/nginx.conf` 的 `client_max_body_size` 和后端 `UPLOAD_MAX_REQUEST_SIZE` 都调大 |
| 排课报 `UnsatisfiedLinkError` / `libstdc++` 相关 | 基础镜像被换成了 alpine。OR-Tools 必须 glibc，用 Debian/Ubuntu 系 |
| 排课任务一直 QUEUED | `APP_SCHEDULING_SOLVER_THREADS` 满了；默认 2 个并发求解，队列 8 |
| 时间差 8 小时 | `TZ`、MySQL `default-time-zone`、`APP_TIME_ZONE` 三处不一致 |
| 改完 `.env.docker` 不生效 | 环境变量在容器创建时注入，需 `up -d` 重建容器，不是 `restart` |

### 和宿主机 MySQL 冲突

本机 3306 通常已被占用，所以 `db` 默认映射到 `127.0.0.1:3307`。只想用容器内的库就把 compose 里 `db.ports` 整段删掉。

## 9. 和裸机部署的取舍

| | Docker（本篇） | 裸机（[12-debian12-install.md](12-debian12-install.md)） |
|---|---|---|
| 依赖管理 | 镜像自带，宿主只需 Docker | 手工装 JDK/Node/Maven/MySQL |
| 升级 | `up -d --build` | 重新打包 jar、systemd 重启 |
| 数据 | 命名卷 `campus-ai-db-data` | `/var/lib/mysql` |
| 排课 OR-Tools | 镜像里已解决 glibc 依赖 | 需要自己确认 libstdc++ |
| 资源占用 | 略高（多一层） | 更低 |
| 适合 | 快速交付、环境一致性要求高 | 单机长期运行、想精细控制 |

两者数据可以互通：把任一方的 `mysqldump` 结果放进 `docker/mysql/initdb/` 即可。
