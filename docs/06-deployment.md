# 06 部署与运维

> 适用版本：0.1.0-SNAPSHOT（阶段八交付）。本文只写**已验证或已实现**的步骤；未验证的推测性方案会明确标注。
>
> **从零装一台服务器**（Debian 12：系统准备 → MySQL 8 → 构建 → systemd → Nginx → HTTPS → 升级/备份/排障）请看
> [12-debian12-install.md](12-debian12-install.md)；本文偏向“已经部署好之后怎么配、怎么运维”。

## 1. 部署形态与前置条件

模块化单体：一个 Spring Boot 进程 + 一个 MySQL 8 库。无 Redis、无 MQ、无外部对象存储。

| 项 | 要求 | 说明 |
|---|---|---|
| JDK | 17（Temurin 17.0.20.1 已验证） | 打包产物为可执行 jar |
| MySQL | 8.0.x / 8.4 LTS（8.0.46 已验证） | 必须支持 InnoDB `ngram` 全文解析器（官方发行版默认支持），否则知识库检索与论坛检索无法建索引 |
| 内存 | 建议 ≥ 1 GB 堆 | 知识库分块与检索在应用内完成 |
| 磁盘 | 取决于知识库规模 | Markdown 原文存数据库，需为 MySQL 预留空间 |
| 时区 | 统一 `Asia/Shanghai` | 应用 `APP_TIME_ZONE` 与数据库会话时区 `DB_SESSION_TIME_ZONE=+08:00` 必须一致 |

多实例部署的已知限制（阶段二起未变）：

- 登录限流是**进程内令牌桶**，多实例各自计数；需要全局限流时接 Redis。
- AI 工具调用的确认记录落数据库，多实例下天然共享，无一致性问题。

## 2. 构建产物

```bash
mvn clean package -DskipTests
# 产物：target/campus-ai-platform-0.1.0-SNAPSHOT.jar
```

运行：

```bash
java -jar campus-ai-platform-0.1.0-SNAPSHOT.jar
```

配置通过环境变量注入（推荐）或 `config/application-local.yml`（已在 `.gitignore` 中）。`config/application-local.yml.example` 提供了本地开发模板。

## 3. 数据库准备

用具备建库/建用户权限的账号执行 `scripts/init-db.sql`（按需修改库名、账号、密码）：

```bash
mysql -h 127.0.0.1 -P 3306 -u root -p < scripts/init-db.sql
```

脚本做三件事：

1. 建库：`utf8mb4` + `utf8mb4_0900_ai_ci`；
2. 建应用账号并只授予该库的 `SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES, DROP`；
3. 输出校验提示（`SELECT @@ngram_token_size`）。

注意：

- 应用账号**不需要** `SUPER`、`FILE`、`GRANT` 权限；Flyway 需要 `CREATE/ALTER/INDEX/REFERENCES/DROP`。
- 生产环境**不要**给应用账号跨库权限。
- `ngram_token_size` 使用默认值 2 即可（检索按 2 字 ngram 设计，与应用侧 `SearchTerms` 一致）。

## 4. 环境变量清单

完整模板见 `.env.example`。带 **[必填]** 的项没有安全默认值。

### 4.1 数据库

| 变量 | 必填 | 默认 | 说明 |
|---|:-:|---|---|
| `DB_URL` |  | `jdbc:mysql://localhost:3306/campus_ai?characterEncoding=UTF-8` | 不要加 `useSSL=false`；MySQL 8 默认 TLS |
| `DB_USERNAME` |  | `campus` | |
| `DB_PASSWORD` | **[必填]** | 空 | 未配置时连接失败 |
| `DB_SESSION_TIME_ZONE` |  | `+08:00` | 使用偏移量，避免 MySQL 未加载时区表时报错 |
| `DB_POOL_MAX` |  | `20` | Hikari 最大连接数 |

### 4.2 安全

| 变量 | 必填 | 默认 | 说明 |
|---|:-:|---|---|
| `APP_JWT_SECRET` | **[必填]** | 空 | 至少 32 字节；不足时**应用拒绝启动**。生成：`openssl rand -base64 48` |
| `APP_JWT_ISSUER` |  | `campus-ai-platform` | |
| `APP_JWT_ACCESS_TTL` |  | `2h` | 改密/退出/停用会使旧令牌立即失效 |
| `APP_CORS_ALLOWED_ORIGINS` |  | 空（不开放跨域） | 逗号分隔，如 `https://campus.example.edu` |
| `APP_SWAGGER_ENABLED` |  | `true` | **生产建议 `false`** |

### 4.3 登录与密码

| 变量 | 默认 | 说明 |
|---|---|---|
| `APP_LOGIN_MAX_FAILED_ATTEMPTS` | `5` | 连续失败次数阈值 |
| `APP_LOGIN_LOCK_DURATION` | `15m` | 临时锁定时长 |
| `APP_LOGIN_RATE_LIMIT_ENABLED` | `true` | 进程内限流开关 |
| `APP_LOGIN_RATE_PER_IP_USER` | `10` | 每 IP+用户名 每分钟 |
| `APP_LOGIN_RATE_PER_IP` | `60` | 每 IP 每分钟 |
| `APP_PASSWORD_MIN_LENGTH` | `8` | 至少包含大小写/数字/符号中的 3 类 |

### 4.4 知识库与检索

| 变量 | 默认 | 说明 |
|---|---|---|
| `APP_KB_MAX_CHUNK_CHARS` | `800` | 单块字符数 |
| `APP_KB_CHUNK_OVERLAP_CHARS` | `120` | 块间重叠 |
| `APP_KB_MAX_FILE_BYTES` | `2097152` | 单文件 2MB |
| `APP_KB_MAX_FILES_PER_BATCH` | `20` | 单批文件数 |
| `APP_KB_IMPORT_ENABLED` | `false` | **仅开发/演示**；生产必须 `false` |
| `APP_SEARCH_TOP_K` / `APP_SEARCH_MAX_TOP_K` | `5` / `20` | 检索条数 |
| `APP_SEARCH_MIN_SCORE` | `0.0` | MySQL ngram 绝对分数很小，默认只过滤非正分 |
| `APP_SEARCH_CANDIDATE_FACTOR` | `4` | 候选放大倍数 |
| `APP_SEARCH_SNIPPET_CHARS` | `300` | 片段长度 |

### 4.5 AI 问答与工具

| 变量 | 默认 | 说明 |
|---|---|---|
| `AI_CHAT_PROVIDER` | `none` | `none` 时 `/ai/chat` 返回 503，不伪造回答；`openai` 走 OpenAI 兼容协议 |
| `AI_BASE_URL` | `https://api.openai.com` | 可指向 DeepSeek/通义兼容端点/本地 Ollama |
| `AI_API_KEY` | 空 | 启用模型时必填 |
| `AI_CHAT_MODEL` | `gpt-4o-mini` | |
| `AI_TEMPERATURE` / `AI_MAX_OUTPUT_TOKENS` | `0.2` / `1024` | |
| `AI_CONNECT_TIMEOUT` / `AI_READ_TIMEOUT` | `10s` / `60s` | |
| `AI_RETRY_MAX_ATTEMPTS` | `2` | Spring AI 默认 10 次，已收紧 |
| `APP_AI_TOP_K` / `APP_AI_MAX_CONTEXT_TOKENS` / `APP_AI_MAX_ANSWER_TOKENS` | `5` / `3000` / `800` | 上下文预算 |
| `APP_AI_HISTORY_MESSAGES` | `6` | 带入提示词的历史条数 |
| `APP_AI_TOOL_CONFIRM_TTL` | `10m` | 写操作工具待确认记录有效期 |

> AI 工具调用本身**不依赖模型**：`/ai/tools`、`/ai/tool-calls` 在没有配置模型时也能工作，便于先接入编排层、后接模型。

### 4.6 初始管理员

| 变量 | 必填 | 说明 |
|---|:-:|---|
| `APP_BOOTSTRAP_ADMIN_USERNAME` |  | 默认 `admin` |
| `APP_BOOTSTRAP_ADMIN_PASSWORD` | 首次启动需要 | 至少 12 位；**仅在系统中没有可用管理员时生效** |
| `APP_BOOTSTRAP_ADMIN_DISPLAY_NAME` |  | 默认「系统管理员」 |

首次登录后建议：立即修改密码，并从部署环境中**移除** `APP_BOOTSTRAP_ADMIN_PASSWORD`。

### 4.7 通用

| 变量 | 默认 | 说明 |
|---|---|---|
| `SERVER_PORT` | `8080` | |
| `APP_TIME_ZONE` | `Asia/Shanghai` | 必须与 `DB_SESSION_TIME_ZONE` 一致 |
| `APP_LOG_PATH` | `./logs` | 日志目录，需可写 |
| `SERVER_FORWARD_HEADERS_STRATEGY` | `none` | 在 Nginx/网关之后设为 `native`，否则审计里的客户端 IP 全是代理 IP |
| `UPLOAD_MAX_FILE_SIZE` / `UPLOAD_MAX_REQUEST_SIZE` | `2MB` / `20MB` | Spring 层上限，知识库模块另有业务上限 |

## 5. 启动与数据库迁移

- 应用启动时 Flyway 自动执行 `classpath:db/migration` 下的 `V1`–`V6`，`validate-on-migrate=true`，`clean-disabled=true`（生产**无法**被误清库）。
- 空库首次启动会建表 + 写入内置角色与 25 个权限点；随后按需创建初始管理员。
- 升级流程：

```bash
# 1) 备份（见第 8 节）
./scripts/backup.sh /backup/campus_ai_before_upgrade.sql.gz

# 2) 替换 jar 并启动；Flyway 会自动应用新增迁移
java -jar campus-ai-platform-<new>.jar

# 3) 校验
curl -fsS http://127.0.0.1:8080/actuator/health
mysql -u campus -p campus_ai -e "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank"
```

- **已发布的迁移脚本不可修改**（`V1`–`V6`）。新增变更一律追加 `V7+`，否则 `validate-on-migrate` 会在启动时报校验失败。
- 迁移失败时应用不会启动，日志中有 Flyway 的具体语句与位置；修复方式：回滚到备份 → 修正脚本（若是新版本）→ 重新启动。不要手工删 `flyway_schema_history` 行。

## 6. 反向代理与 HTTPS

Nginx 示例（TLS 由 Nginx 终止，应用只监听内网）：

```nginx
server {
    listen 443 ssl;
    server_name campus.example.edu;

    ssl_certificate     /etc/nginx/certs/campus.crt;
    ssl_certificate_key /etc/nginx/certs/campus.key;

    client_max_body_size 20m;          # 与 UPLOAD_MAX_REQUEST_SIZE 对齐

    location / {
        proxy_pass         http://127.0.0.1:8080;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
        proxy_read_timeout 120s;       # AI 问答可能较慢
    }
}
```

配套设置：

- `SERVER_FORWARD_HEADERS_STRATEGY=native`，否则 `audit_log.client_ip` 记录的是代理地址。
- `APP_CORS_ALLOWED_ORIGINS` 只填真实前端域名；留空则完全不开放跨域（同源部署无需配置）。
- 应用不写 Cookie、不使用会话，CSRF 关闭是安全的；但**必须**通过 HTTPS 传输，避免令牌被嗅探。
- 请求追踪：客户端可传 `X-Request-Id`（仅接受 `[A-Za-z0-9_-]{8,64}`），响应头返回 `X-Trace-Id`，与日志、审计的 `trace_id` 一致。

## 7. 日志与审计

### 7.1 应用日志

- 位置：`APP_LOG_PATH`（默认 `./logs`），按天滚动，见 `logback-spring.xml`。
- 脱敏：`%maskedMsg` 自动遮蔽 password、token、apiKey、`Bearer`、JWT、`sk-` 等；不打印 SQL 参数与请求体。
- 级别：默认 `INFO`；`com.campus.platform=DEBUG` 会输出 SQL 参数，**生产不要开**。
- 访问日志：每个请求一行（方法、路径、状态、耗时、traceId、用户 ID），可用于排查慢接口。

### 7.2 审计日志

- 表 `audit_log` 只追加、不更新删除，记录登录、用户/组织变更、知识库、论坛、课程、选课、请假、AI 工具调用。
- `source` 区分 `API`/`AI_TOOL`/`SYSTEM`；`detail` 为脱敏后的 JSON 摘要（≤2000 字），不含正文全文与密码。
- 留存建议：至少 180 天；如需归档，按 `created_at` 导出后删除旧分区/旧数据（当前无内置清理任务，需要运维侧定时任务）。
- 查询接口 `GET /api/v1/audit-logs` 需要 `audit:read`（仅管理员）。

## 8. 备份与恢复

```bash
# 备份（默认 gzip 压缩，包含建表与数据，不含 CREATE DATABASE）
./scripts/backup.sh /backup/campus_ai_$(date +%F_%H%M).sql.gz

# 恢复
gunzip -c /backup/campus_ai_2026-10-27_2300.sql.gz | mysql -h 127.0.0.1 -u campus -p campus_ai
```

要点：

- 备份前确认没有正在执行的迁移；升级前**必须**备份。
- 备份包含 `flyway_schema_history`，恢复后 Flyway 不会重复执行已应用的迁移。
- 建议每天全量备份 + binlog 增量；恢复演练每季度一次。
- 恢复后校验：`/actuator/health`、管理员登录、`SELECT COUNT(*) FROM flyway_schema_history` 与备份前一致。

## 9. 健康检查与监控

| 项 | 用法 |
|---|---|
| 存活/就绪 | `GET /actuator/health`（公开，不返回细节）；返回 `{"status":"UP"}` |
| 系统信息 | `GET /api/v1/system/info`（公开）：服务名、API 版本、服务器时间、业务时区 |
| 追踪 | 响应头 `X-Trace-Id`；日志与审计可按 `trace_id` 关联 |
| 关键指标（建议采集） | 登录失败次数、429 次数、`/ai/chat` 503 次数、工具 `FAILED` 记录数、慢请求（>1s） |

健康检查不校验数据库连接细节（`show-details: never`），如需深度检查请用外部探针执行 `SELECT 1`。

## 10. 冒烟验证

部署后执行：

```bash
BASE_URL=http://127.0.0.1:8080 \
SMOKE_USERNAME=admin SMOKE_PASSWORD='<管理员密码>' \
./scripts/smoke.sh
```

脚本按顺序验证：

1. `/actuator/health` 为 `UP`；
2. 登录拿到令牌；
3. `GET /ai/tools` 返回工具目录（只读接口，不需要模型）；
4. `POST /ai/tool-calls` 调用只读工具 `searchForumPosts`，状态 `SUCCEEDED`；
5. 写工具 `createForumPost` 返回 `PENDING_CONFIRM`（**不执行**），随后 `/reject` 拒绝，确认没有真的发帖。

任一步失败脚本以非 0 退出，并打印响应体。脚本只使用 `curl` 与 `sed`/`grep`，不依赖 `jq`。

> 为什么冒烟不确认执行写工具：冒烟会在生产库留下业务数据。写工具的“确认执行”路径由集成测试 `AiToolIT`、`SystemFlowIT` 覆盖。

## 11. 常见故障排查

| 现象 | 可能原因 | 处理 |
|---|---|---|
| 启动失败：`未配置 JWT 签名密钥` | `APP_JWT_SECRET` 缺失或不足 32 字节 | 设置足够长的随机密钥 |
| 启动失败：`Flyway validate failed` | 改动了已发布的迁移脚本，或手工改过 `flyway_schema_history` | 恢复脚本原状；用备份恢复数据库 |
| 启动失败：`Unknown database` | 未执行 `scripts/init-db.sql` | 建库并授权 |
| 启动失败：`Access denied` | 账号/密码错误或权限不足（Flyway 需要 DDL） | 核对 `DB_*` 与授权 |
| 知识库上传/检索报错：`ngram` 相关 | MySQL 不支持 ngram 解析器 | 换官方 MySQL 8 发行版 |
| 登录 401 `AUTH_BAD_CREDENTIALS` | 用户名/密码错误（两者提示相同，防枚举） | 核对账号；管理员可重置密码 |
| 登录 403 `AUTH_ACCOUNT_LOCKED` | 连续失败锁定，或管理员锁定 | 等待锁定期结束，或管理员在用户详情解锁 |
| 登录 429 `TOO_MANY_REQUESTS` | 触发限流 | 按 `Retry-After` 重试；多实例时注意各自计数 |
| 接口 401 但令牌刚签发 | 改密/退出/停用导致 `token_version` 递增 | 重新登录 |
| `/ai/chat` 503 | `AI_CHAT_PROVIDER=none` 或模型不可用 | 配置模型；或仅使用工具接口 |
| AI 回答“没有找到足够依据” | 检索为空或低于阈值 | 确认文档已导入、用户对空间有读权限、换更具体的关键词 |
| 工具确认返回 409 `AI_TOOL_CONFIRM_EXPIRED` | 超过 `APP_AI_TOOL_CONFIRM_TTL` | 重新发起工具调用 |
| 工具确认返回 409 `AI_TOOL_STATUS_INVALID` | 已确认/已拒绝/并发确认 | 刷新记录状态，不要重复确认 |
| 审计里客户端 IP 都是代理地址 | 未设置 forward headers | `SERVER_FORWARD_HEADERS_STRATEGY=native` |
| 时间差 8 小时 | `APP_TIME_ZONE` 与 `DB_SESSION_TIME_ZONE` 不一致 | 统一为 `Asia/Shanghai` / `+08:00` |

## 12. 上线安全清单

- [ ] `APP_JWT_SECRET` 为随机值（≥32 字节），未写入代码库或镜像
- [ ] `DB_PASSWORD`、`AI_API_KEY` 只通过环境变量/密钥管理注入
- [ ] 首次登录后修改初始管理员密码，并从环境中移除 `APP_BOOTSTRAP_ADMIN_PASSWORD`
- [ ] `APP_SWAGGER_ENABLED=false`
- [ ] `APP_CORS_ALLOWED_ORIGINS` 仅包含真实前端域名（或留空）
- [ ] `APP_KB_IMPORT_ENABLED=false`
- [ ] `SERVER_FORWARD_HEADERS_STRATEGY=native`（在代理之后）
- [ ] 全站 HTTPS，应用端口只监听内网
- [ ] 数据库账号最小权限，未授予跨库与 `SUPER`
- [ ] 备份任务已配置并做过一次恢复演练
- [ ] 审计留存策略与日志轮转已确认
