# 12 Debian 12 安装与部署（Linux 优先）

> 适用：全新安装的 **Debian 12 (bookworm) x86_64**，从裸机到可访问的完整站点。
> 部署形态：**MySQL 8 + 后端可执行 jar（systemd 托管）+ 前端静态站（Nginx 反代 `/api`）**。
> Windows 只在[附录 A](#附录-awindows-部署次要) 简单带过。

已验证的软件组合：

| 组件 | 版本 | 说明 |
|---|---|---|
| Debian | 12 (bookworm) | glibc 2.36，满足 OR-Tools 原生库要求 |
| OpenJDK | 17（`openjdk-17-jdk-headless`） | 打包产物是 jar，运行只需 JRE |
| MySQL | 8.0.x 或 8.4 LTS | **不能用 MariaDB**，见 [3.1](#31-为什么必须是-mysql-8-而不是-debian-自带的-mariadb) |
| Maven | 3.9.x（构建用） | 只在构建机需要，运行机可不装 |
| Node.js | 22.x（构建用） | `frontend/package.json` 要求 `>=22.12.0` |
| Nginx | 1.22（Debian 源） | 静态站 + 反向代理 |

推荐机器规格：**2 核 4 GB 内存 / 40 GB 磁盘**。
最低 2 GB 内存也能跑，但要配 swap，且自动排课求解时容易吃紧（CP-SAT 是内存型求解器）。

---

## 0. 目录与端口规划

下文统一使用这套约定，可按需替换（替换时注意 Nginx 与 systemd 里的路径要同步改）：

| 用途 | 路径 |
|---|---|
| 程序目录（jar、临时文件） | `/opt/campus-ai` |
| 前端静态文件 | `/var/www/campus-ai` |
| 环境变量文件（含密钥） | `/etc/campus-ai/env` |
| 日志目录 | `/var/log/campus-ai` |
| 数据库备份 | `/var/backups/campus-ai` |

| 端口 | 用途 | 对外 |
|---|---|---|
| 80 / 443 | Nginx（站点入口） | ✅ |
| 8080 | 后端 Spring Boot | ❌ 只监听 127.0.0.1 |
| 3306 | MySQL | ❌ 只监听 127.0.0.1 |

请求链路：浏览器 → Nginx（静态文件 + `/api` 反代）→ 后端 8080 → MySQL 3306。

---

## 1. 系统准备

以下命令默认用有 sudo 权限的普通用户执行（不建议日常用 root）。

```bash
sudo apt update
sudo apt -y upgrade
sudo apt -y install curl wget gnupg ca-certificates lsb-release \
                    git vim unzip jq ufw nginx

# 时区：必须与后端 APP_TIME_ZONE、数据库会话时区一致
sudo timedatectl set-timezone Asia/Shanghai
timedatectl | grep "Time zone"
```

### 1.1 创建运行账号

后端不以 root 运行：

```bash
sudo useradd --system --create-home --shell /usr/sbin/nologin campus
sudo mkdir -p /opt/campus-ai /var/log/campus-ai /etc/campus-ai /var/backups/campus-ai
sudo chown -R campus:campus /opt/campus-ai /var/log/campus-ai
sudo chmod 750 /etc/campus-ai
```

### 1.2 swap（内存 ≤ 2 GB 时建议）

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
free -h
```

### 1.3 防火墙

```bash
sudo ufw allow OpenSSH
sudo ufw allow 'Nginx Full'      # 80 + 443
sudo ufw enable
sudo ufw status
```

8080 与 3306 **不要**对外开放。

---

## 2. 安装 JDK 17

Debian 12 官方源自带 OpenJDK 17：

```bash
sudo apt -y install openjdk-17-jdk-headless
java -version
# openjdk version "17.0.x" ...
```

`-headless` 版本不带图形库，服务器够用。
如果机器上装了多个 JDK，用 `update-alternatives --config java` 选 17。

---

## 3. 安装 MySQL 8

### 3.1 为什么必须是 MySQL 8（而不是 Debian 自带的 MariaDB）

Debian 12 的 `default-mysql-server` 实际是 **MariaDB**，而本项目用了 MySQL 8 独有的：

- 排序规则 `utf8mb4_0900_ai_ci`（迁移脚本 V1 起就在用，MariaDB 不认）；
- InnoDB **`ngram` 全文解析器**（中文知识库检索、论坛检索、课程/帖子搜索全靠它，`ngram_token_size=2`）。

用 MariaDB 会在 Flyway 建表时直接失败（未知排序规则），所以必须装官方 MySQL 8。

### 3.2 添加 MySQL APT 源

从 <https://dev.mysql.com/downloads/repo/apt/> 取最新的 `mysql-apt-config_*_all.deb`（下文以 0.8.33 为例）：

```bash
cd /tmp
wget https://repo.mysql.com/mysql-apt-config_0.8.33-1_all.deb
sudo dpkg -i mysql-apt-config_0.8.33-1_all.deb
```

弹出配置界面时：**MySQL Server & Cluster → 选 `mysql-8.0`**（或 8.4 LTS）→ `Ok` 保存退出。

```bash
sudo apt update
sudo apt -y install mysql-server mysql-client
```

安装过程中会要求设置 **root 密码**（务必记下；Debian 上的 MySQL 官方包默认用密码认证，而不是 `auth_socket`）。

```bash
sudo systemctl enable --now mysql
sudo systemctl status mysql --no-pager
mysql --version
```

### 3.3 服务端参数

新建 `/etc/mysql/mysql.conf.d/zz-campus.cnf`：

```ini
[mysqld]
# 字符集：必须 utf8mb4，否则中文/emoji 会截断
character-set-server = utf8mb4
collation-server     = utf8mb4_0900_ai_ci

# 中文检索的核心：2 字 ngram，与后端 SearchTerms 的分词口径一致
ngram_token_size = 2

# 时区：与 APP_TIME_ZONE 保持一致（用偏移量，避免未加载时区表时报错）
default-time-zone = '+08:00'

# 4 GB 内存机器的保守值；内存越大越好，建议给到物理内存的 50%~60%
innodb_buffer_pool_size = 1G
innodb_log_file_size    = 256M
max_connections         = 200

# 慢查询
slow_query_log      = 1
slow_query_log_file = /var/log/mysql/slow.log
long_query_time     = 2
```

```bash
sudo systemctl restart mysql

# 校验：两个值必须正确，否则中文检索会“查得到但没结果”
sudo mysql -u root -p -e "SELECT @@version, @@ngram_token_size, @@character_set_server, @@collation_server, @@time_zone;"
```

期望：`ngram_token_size = 2`、`character_set_server = utf8mb4`、`collation_server = utf8mb4_0900_ai_ci`、`time_zone = +08:00`。

### 3.4 安全加固（可选但推荐）

```bash
sudo mysql_secure_installation
# 删除匿名用户、禁止 root 远程登录、删除 test 库
```

确认 MySQL 只监听本机：

```bash
sudo ss -lntp | grep 3306
# 期望 127.0.0.1:3306，而不是 0.0.0.0:3306
```

---

## 4. 安装 Node.js 22（仅构建前端用）

`frontend/package.json` 里 `engines.node` 要求 `>=22.12.0`，Debian 源里的 Node 18 太旧，用 NodeSource：

```bash
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
sudo apt -y install nodejs
node -v && npm -v
```

> 不想在服务器装 Node：也可以在自己的电脑/CI 上跑 `npm ci && npm run build`，把 `frontend/dist` 用 `rsync` 传上去（见 [9.1](#91-构建前端)）。服务器只需要 Nginx。

---

## 5. 构建后端 jar

### 5.1 安装 Maven

Debian 源里是 3.8.7，多数情况可用；若构建时报插件解析错误，换成官方 3.9.x：

```bash
cd /tmp
MAVEN_VER=3.9.9
wget -q https://dlcdn.apache.org/maven/maven-3/${MAVEN_VER}/binaries/apache-maven-${MAVEN_VER}-bin.tar.gz
sudo mkdir -p /opt/maven
sudo tar -xzf apache-maven-${MAVEN_VER}-bin.tar.gz -C /opt/maven --strip-components=1
sudo ln -sf /opt/maven/bin/mvn /usr/local/bin/mvn
mvn -v
```

> 网络受限时可在 `~/.m2/settings.xml` 配国内镜像（阿里云等）。

### 5.2 拉代码并打包

```bash
sudo mkdir -p /opt/campus-ai/src
sudo chown $USER:$USER /opt/campus-ai/src
git clone https://github.com/qita-link/university-ai-platform.git /opt/campus-ai/src
cd /opt/campus-ai/src
mvn -DskipTests clean package
ls -lh target/campus-ai-platform-0.1.0-SNAPSHOT.jar
```

小内存机器建议限制 Maven 堆：

```bash
MAVEN_OPTS="-Xmx1g" mvn -DskipTests clean package
```

`-DskipTests` 会跳过测试；跑完整测试需要一个可清空的 MySQL 测试库（见 README「测试」一节），生产机上一般不需要。

### 5.3 放到程序目录

```bash
sudo install -o campus -g campus -m 0640 \
  target/campus-ai-platform-0.1.0-SNAPSHOT.jar /opt/campus-ai/app.jar
```

版本号会随 `pom.xml` 变化，后续升级时重复这一步即可（见 [10](#10-升级与回滚)）。

---

## 6. 建库与账号

项目自带脚本会把库、账号、权限都建好（**先把密码改掉**）：

```bash
cd /opt/campus-ai/src
# 生成强密码，记下来，下一步写进环境变量文件
openssl rand -base64 24

# 修改脚本里的 change-me-strong-password 后再执行
sudo mysql -u root -p < scripts/init-db.sql
```

脚本做三件事：

1. 建库 `campus_ai`（`utf8mb4` + `utf8mb4_0900_ai_ci`）；
2. 建账号 `campus@'%'`，**只授予该库**的 `SELECT/INSERT/UPDATE/DELETE/CREATE/ALTER/INDEX/REFERENCES/DROP`（Flyway 需要后四个 DDL 权限，不需要 `SUPER`/`FILE`/`GRANT`）；
3. 打印 `@@ngram_token_size` 等校验信息。

验证账号能登录：

```bash
mysql -h 127.0.0.1 -u campus -p -e "SHOW DATABASES; SELECT @@ngram_token_size;"
```

表结构不用手工建：**首次启动时 Flyway 会自动执行 `V1`–`V9` 迁移**（`validate-on-migrate=true`，`clean-disabled=true`，生产环境不会被误清库）。

### 6.1 从既有环境迁移数据（可选）

如果是从旧机器搬过来，先在旧机器导出（项目脚本：`scripts/backup.sh`），再到新机器恢复：

```bash
# 旧机器
DB_PASSWORD='***' ./scripts/backup.sh /tmp/campus_ai.sql.gz

# 新机器：先建空库（上面第 6 步），再导入
gunzip -c campus_ai.sql.gz | mysql -h 127.0.0.1 -u root -p campus_ai
```

备份文件里含 `flyway_schema_history`，导入后 Flyway 会认为迁移已应用，不会再跑一遍——这正是想要的行为。
导入后核对一下：`SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='campus_ai';`（完整库为 55 张表）。

---

## 7. 配置与首次启动

### 7.1 环境变量文件

敏感信息（数据库密码、JWT 密钥、管理员初始密码）都走环境变量，**不要写进代码或提交到仓库**。

```bash
sudo tee /etc/campus-ai/env >/dev/null <<'EOF'
# ---------- 数据库 ----------
DB_URL=jdbc:mysql://127.0.0.1:3306/campus_ai?characterEncoding=UTF-8
DB_USERNAME=campus
DB_PASSWORD=请替换为init-db.sql里设置的密码
DB_SESSION_TIME_ZONE=+08:00
DB_POOL_MAX=20

# ---------- 安全 ----------
# openssl rand -base64 48 生成；至少 32 字节，否则应用拒绝启动
APP_JWT_SECRET=请替换为openssl生成的值
APP_SWAGGER_ENABLED=false
# 同源部署（Nginx 反代）不需要开 CORS；前后端分域名时才填
# APP_CORS_ALLOWED_ORIGINS=https://campus.example.edu

# ---------- 首次启动的管理员（登录成功后请删掉这一行） ----------
APP_BOOTSTRAP_ADMIN_USERNAME=admin
APP_BOOTSTRAP_ADMIN_PASSWORD=请替换为至少12位的强密码

# ---------- 通用 ----------
SERVER_PORT=8080
APP_TIME_ZONE=Asia/Shanghai
APP_LOG_PATH=/var/log/campus-ai
# 在 Nginx 之后必须设成 native，否则审计日志里的客户端 IP 全是 127.0.0.1
SERVER_FORWARD_HEADERS_STRATEGY=native
UPLOAD_MAX_FILE_SIZE=2MB
UPLOAD_MAX_REQUEST_SIZE=20MB

# ---------- AI 问答（不配也能跑，/ai/chat 会明确返回 503） ----------
AI_CHAT_PROVIDER=none
# AI_CHAT_PROVIDER=openai
# AI_BASE_URL=https://api.deepseek.com
# AI_API_KEY=sk-xxx
# AI_CHAT_MODEL=deepseek-chat
EOF

sudo chown root:campus /etc/campus-ai/env
sudo chmod 640 /etc/campus-ai/env
```

要点：

- `APP_JWT_SECRET` 一旦更换，**所有已登录用户的令牌立即失效**（数据不受影响），换机器/重装时要留意；
- `DB_SESSION_TIME_ZONE` 与 `APP_TIME_ZONE` 必须一致，否则页面时间会差 8 小时；
- 环境变量优先级高于 `config/application-local.yml`；Tomcat/systemd 里若残留 `SERVER_PORT`，以最后一次赋值为准（本地 Windows 上就遇到过被别的程序注入端口的情况）。

### 7.2 先在前台试跑一次

```bash
cd /opt/campus-ai
sudo -u campus env $(sudo grep -v '^#' /etc/campus-ai/env | xargs) \
  java -Xms512m -Xmx1536m -Duser.timezone=Asia/Shanghai \
  -jar /opt/campus-ai/app.jar --server.port=8080
```

> 上面这种 `env $(...)` 写法仅用于临时验证；正式运行用 systemd 的 `EnvironmentFile`（下一步）。
> 若密码里有空格或 `$`，别用这行，直接改用 systemd 启动。

看到 `Started CampusAiApplication in xx seconds` 即成功。另开一个终端冒烟：

```bash
curl -s http://127.0.0.1:8080/actuator/health          # {"status":"UP"}
curl -s http://127.0.0.1:8080/api/v1/system/info | jq  # 系统信息

# 管理员登录（用 APP_BOOTSTRAP_ADMIN_* 里的密码）
curl -s -X POST http://127.0.0.1:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"你的初始密码"}' | jq '.data.user'
```

登录成功后：**立刻改密码**，然后把 `APP_BOOTSTRAP_ADMIN_PASSWORD` 从 env 文件里删掉并重启（该变量只在“系统里没有可用管理员”时生效，留着是风险）。

`Ctrl+C` 停掉前台进程，接下来交给 systemd。

---

## 8. systemd 托管后端

### 8.1 单元文件

```bash
sudo tee /etc/systemd/system/campus-ai.service >/dev/null <<'EOF'
[Unit]
Description=Campus AI Platform (Spring Boot)
Documentation=https://github.com/qita-link/university-ai-platform
After=network-online.target mysql.service
Wants=network-online.target

[Service]
Type=simple
User=campus
Group=campus
WorkingDirectory=/opt/campus-ai
EnvironmentFile=/etc/campus-ai/env
# 堆大小按机器内存调整：4G 机器 1.5G 比较稳（排课求解时会有峰值）
ExecStart=/usr/bin/java -Xms512m -Xmx1536m \
  -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8 \
  -jar /opt/campus-ai/app.jar
Restart=always
RestartSec=5
SuccessExitStatus=143
LimitNOFILE=65535
# 不允许提权
NoNewPrivileges=true
PrivateTmp=false

[Install]
WantedBy=multi-user.target
EOF
```

> `PrivateTmp=false` 是有意为之：OR-Tools 会把原生库解压到 `java.io.tmpdir` 再 `dlopen`，
> 若 `/tmp` 挂载了 `noexec`，需要额外加 `-Djava.io.tmpdir=/opt/campus-ai/tmp`（目录要给 campus 可写）。

### 8.2 启动与自启

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now campus-ai
systemctl status campus-ai --no-pager
curl -s http://127.0.0.1:8080/actuator/health
```

### 8.3 日志

日志同时写到 journal 和 `APP_LOG_PATH`（默认 `/var/log/campus-ai`）：

```bash
journalctl -u campus-ai -f             # 实时
journalctl -u campus-ai --since today  # 今天
grep -c ERROR /var/log/campus-ai/*.log # 错误计数
```

轮转（可选，文件日志太长时）：

```bash
sudo tee /etc/logrotate.d/campus-ai >/dev/null <<'EOF'
/var/log/campus-ai/*.log {
    daily
    rotate 14
    compress
    missingok
    notifempty
    copytruncate
    su campus campus
}
EOF
```

---

## 9. Nginx：静态站 + 反向代理

### 9.1 构建前端

```bash
cd /opt/campus-ai/src/frontend
npm ci
npm run build          # 产物：frontend/dist
```

> 构建机与服务器分离时，本机执行 `npm ci && npm run build`，然后
> `rsync -av --delete frontend/dist/ user@server:/tmp/campus-dist/`。
> **不要**提交 `node_modules` 与 `dist` 到仓库。

发布静态文件：

```bash
sudo mkdir -p /var/www/campus-ai
sudo rsync -a --delete /opt/campus-ai/src/frontend/dist/ /var/www/campus-ai/
sudo chown -R www-data:www-data /var/www/campus-ai
```

### 9.2 站点配置

```bash
sudo tee /etc/nginx/sites-available/campus-ai >/dev/null <<'EOF'
server {
    listen 80;
    listen [::]:80;
    server_name campus.example.edu;      # 改成你的域名或服务器 IP

    root /var/www/campus-ai;
    index index.html;

    # 知识库上传：Spring 层上限 20MB，这里必须不小于它，否则先被 Nginx 拦成 413
    client_max_body_size 20m;

    gzip on;
    gzip_comp_level 5;
    gzip_min_length 1024;
    gzip_types text/plain text/css application/javascript application/json
               image/svg+xml application/xml;

    access_log /var/log/nginx/campus-ai.access.log;
    error_log  /var/log/nginx/campus-ai.error.log;

    # 带 hash 的静态资源可以长缓存
    location /assets/ {
        expires 30d;
        add_header Cache-Control "public, immutable";
        try_files $uri =404;
    }

    # index.html 不缓存，避免发版后浏览器仍打开旧版本
    location = /index.html {
        add_header Cache-Control "no-store";
    }

    # 后端 API
    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        # 自动排课求解、AI 问答可能比较久，别用默认 60s
        proxy_connect_timeout 10s;
        proxy_send_timeout    300s;
        proxy_read_timeout    300s;
    }

    # 健康检查只给本机看
    location /actuator/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        allow 127.0.0.1;
        allow ::1;
        deny all;
    }

    # 单页应用：刷新 /courses、/scheduling 这类子路由不能 404
    location / {
        try_files $uri $uri/ /index.html;
    }
}
EOF

sudo ln -sf /etc/nginx/sites-available/campus-ai /etc/nginx/sites-enabled/campus-ai
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t
sudo systemctl reload nginx
```

访问 `http://campus.example.edu` 应能看到登录页。用 `admin` + 初始密码登录（若改过就按新的来）。

### 9.3 HTTPS（有域名时强烈建议）

```bash
sudo apt -y install certbot python3-certbot-nginx
sudo certbot --nginx -d campus.example.edu
sudo systemctl list-timers | grep certbot   # 自动续期
```

启用 HTTPS 后如果前后端分域名部署，记得在 `/etc/campus-ai/env` 里补 `APP_CORS_ALLOWED_ORIGINS`。

---

## 10. 升级与回滚

```bash
cd /opt/campus-ai/src

# 1) 备份数据库（重要，Flyway 迁移不可回退）
DB_PASSWORD='***' ./scripts/backup.sh /var/backups/campus-ai/before-upgrade-$(date +%F).sql.gz

# 2) 拉新代码、构建
git pull
mvn -DskipTests clean package

# 3) 替换 jar（旧 jar 留一份）
sudo cp /opt/campus-ai/app.jar /opt/campus-ai/app.jar.bak
sudo install -o campus -g campus -m 0640 \
  target/campus-ai-platform-0.1.0-SNAPSHOT.jar /opt/campus-ai/app.jar

# 4) 重启（Flyway 会自动应用新增迁移）
sudo systemctl restart campus-ai
sleep 20
curl -s http://127.0.0.1:8080/actuator/health

# 5) 前端有改动时同步静态文件
sudo rsync -a --delete /opt/campus-ai/src/frontend/dist/ /var/www/campus-ai/
```

回滚：把 `app.jar.bak` 换回去并重启；若新版本已执行过数据库迁移，需要从备份恢复数据库（所以第 1 步别省）。

---

## 11. 备份

```bash
# 手动
cd /opt/campus-ai/src
DB_PASSWORD='***' ./scripts/backup.sh /var/backups/campus-ai/campus_ai-$(date +%F_%H%M).sql.gz

# 每日 02:30 自动备份，保留 14 天
sudo tee /etc/cron.d/campus-ai-backup >/dev/null <<'EOF'
30 2 * * * root cd /opt/campus-ai/src && DB_PASSWORD='***' ./scripts/backup.sh /var/backups/campus-ai/campus_ai-$(date +\%F).sql.gz && find /var/backups/campus-ai -name 'campus_ai-*.sql.gz' -mtime +14 -delete
EOF
sudo chmod 600 /etc/cron.d/campus-ai-backup
```

cron 里 `%` 必须转义成 `\%`。
`scripts/backup.sh` 用 `mysqldump --single-transaction`（不锁表），输出不含 `CREATE DATABASE`，恢复前要先建库。
备份文件建议再同步一份到别的机器/对象存储——只留在同一块盘上不算备份。

---

## 12. 常见故障

| 现象 | 原因与处理 |
|---|---|
| Flyway 启动报 `Unknown collation: 'utf8mb4_0900_ai_ci'` | 装成了 MariaDB。卸掉 `default-mysql-server`，按 [3.2](#32-添加-mysql-apt-源) 装官方 MySQL 8 |
| 中文检索“有数据但搜不到” | `ngram_token_size` 不是 2，或改完后没有重建索引。改 `zz-campus.cnf` 重启 MySQL，然后重新导入/重建知识库文档 |
| `/actuator/health` 不通、服务反复重启 | 看 `journalctl -u campus-ai -n 100`。最常见是 `DB_PASSWORD` 错、`APP_JWT_SECRET` 少于 32 字节、8080 被占用 |
| 启动日志说端口是别的值 | 环境里存在遗留的 `SERVER_PORT`。systemd 单元里显式传 `--server.port=8080`（本项目单元已带 `EnvironmentFile`，可再确认优先级） |
| 所有用户突然要重新登录 | `APP_JWT_SECRET` 变了（重装/换机器）。属正常，重新登录即可 |
| Nginx 502 Bad Gateway | 后端没起 / 端口不匹配。`systemctl status campus-ai`，`ss -lntp \| grep 8080` |
| 刷新 `/courses` 出现 404 | Nginx 少了 SPA 回退：`try_files $uri $uri/ /index.html` |
| 上传文件报 413 | `client_max_body_size` 小于应用上限（`UPLOAD_MAX_REQUEST_SIZE`，默认 20MB） |
| 页面时间差 8 小时 | `APP_TIME_ZONE` 与 `DB_SESSION_TIME_ZONE` 不一致，或 MySQL `default-time-zone` 没设 |
| 审计日志里客户端 IP 全是 127.0.0.1 | 少了 `SERVER_FORWARD_HEADERS_STRATEGY=native` |
| 自动排课一点就失败 / 报 `UnsatisfiedLinkError` | 原生库无法加载：确认 `/tmp` 没挂 `noexec`（否则加 `-Djava.io.tmpdir=/opt/campus-ai/tmp`），确认是 x86_64 或 arm64、且是 glibc 系统（Debian 满足） |
| 排课求解很慢或被 OOM 杀掉 | CP-SAT 吃内存。加大 `-Xmx`，或缩小求解范围（按学院/年级分批）；`dmesg \| grep -i oom` 可确认 |
| 首次登录提示“没有可用的管理员” | 设置 `APP_BOOTSTRAP_ADMIN_PASSWORD` 后重启；或库里已有管理员时该变量不生效，用现有账号登录 |
| 登录 403 `AUTH_ACCOUNT_LOCKED` | 连续失败 5 次锁 15 分钟（`APP_LOGIN_MAX_FAILED_ATTEMPTS` / `APP_LOGIN_LOCK_DURATION`），管理员可在用户详情里解锁 |

排查顺序建议：`systemctl status` → `journalctl -u campus-ai` → `curl 127.0.0.1:8080/actuator/health` → `nginx -t` + Nginx error log → MySQL 慢查询/连接数。

---

## 13. 可选配置

### 13.1 接入大模型

不配置也能用（`/ai/chat` 明确返回 503，不伪造回答）；要真正问答：

```bash
sudo vim /etc/campus-ai/env
# AI_CHAT_PROVIDER=openai
# AI_BASE_URL=https://api.deepseek.com        # 任意 OpenAI 兼容端点，包括本地 Ollama
# AI_API_KEY=sk-xxx
# AI_CHAT_MODEL=deepseek-chat
sudo systemctl restart campus-ai
```

### 13.2 本地语料导入（仅开发/演示）

`APP_KB_IMPORT_ENABLED=true` 可在启动时把 Markdown 目录按幂等规则导入指定知识库。**生产环境保持 `false`**（README「本地知识库语料导入」有详细说明）。

### 13.3 自动排课相关

- 排课依赖 OR-Tools CP-SAT，原生库随 jar 分发，Linux 无需额外安装；
- 学期/作息/教学日历/约束权重在「排课 → 排课设置」里配；教师可来时间（外聘教师只能某天来）也只有管理员能设；
- 更细的部署与容量说明见 [11-scheduling-deployment.md](11-scheduling-deployment.md)。

---

## 附录 A：Windows 部署（次要）

Linux 是推荐路径；如果只在 Windows 上试用，最短路径是：

1. 装 JDK 17、Maven、**MySQL 8**（不是 MariaDB、不是 SQLite）；
2. `mvn -DskipTests package`，双击 `deploy\start-backend.cmd`（已内置 `--server.port=8080`，避免被环境里遗留的 `SERVER_PORT` 改掉端口）；
3. `cd frontend && npm ci && npm run build`，双击 `deploy\start-frontend.cmd`（Node 静态托管 + `/api` 反代，端口 5173）；
4. 浏览器打开 <http://127.0.0.1:5173>。

两个 Windows 专属的坑：

- **OR-Tools 崩溃**：Oracle JDK 的 `bin\msvcp140.dll`（14.36）会盖住系统里的新版运行库，导致一求解就 `EXCEPTION_ACCESS_VIOLATION`。处理办法见 [11-scheduling-deployment.md §1.1](11-scheduling-deployment.md)。
- **端口被改**：某些工具会往环境里塞 `SERVER_PORT` / `SERVER__PORT`，Spring 会把它们当成 `server.port`。启动参数里显式写 `--server.port=8080`。

其余配置项与 Linux 完全一致（环境变量或 `config/application-local.yml`）。
