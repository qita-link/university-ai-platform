# 高校 AI 综合服务平台 · 后端

面向学生、教师和管理员的校园服务后端，把 AI 知识库问答与论坛、课程、选课、请假等业务系统结合起来。采用模块化单体架构：Java 17 + Spring Boot 3.4 + MyBatis-Plus + MySQL 8 + Spring Security（JWT）+ Spring AI。

当前进度：八个阶段完成（2026-10-27），**自动排课模块第一阶段后端完成**（2026-10-29），全量测试 80 单元 + 146 集成 = 226 通过。部署步骤见 [docs/06-deployment.md](docs/06-deployment.md)；自动排课的设计、接口、数据库、测试与部署见 [docs/07-scheduling.md](docs/07-scheduling.md) 起的 5 篇文档。阶段四的 Qdrant/Embedding 向量检索仍未接入，接口已预留。详见 [docs/01-development-plan.md](docs/01-development-plan.md)。

## 已完成能力

- 登录、退出、修改本人密码；连续失败锁定、登录/改密限流、防账号枚举、审计。
- 管理员用户管理：分页查询、创建、修改、启停/锁定、解锁、分配角色、重置密码。
- 自我保护与“至少保留一个可用管理员”规则；角色授予不能超过操作人自身权限。
- 组织树管理（学院/系/专业/班级），层级校验、环检测、启停约束。
- `DataScopeService` 组织数据权限基础设施，供知识库、课程、请假等模块复用。
- 三种内置角色（ADMIN/TEACHER/STUDENT）与 25 个权限点；角色和权限点只读。
- 知识库分类/空间/成员授权；Markdown 批量上传、在线编辑、版本、分块、软删除与重处理。
- 知识库可见范围 `PUBLIC/ORG/PRIVATE/COURSE`，组织范围复用 `DataScopeService`；`COURSE` 按“任课教师 + 有效选课学生”判定。
- 可选本地语料导入：启动时把 Markdown 目录按幂等方式导入指定知识库（默认关闭）。
- 中文关键词检索：标题加权、2 字 ngram、去重、阈值、空间权限过滤，`POST /knowledge/search`。
- AI 知识库问答：上下文预算、引用、Token 统计、会话记录；无依据不调用模型，未配置模型明确 503。
- 论坛：分类管理、发帖/编辑/软删除、两级评论（楼中楼）、分页、中文关键词检索、置顶、隐藏/恢复、审计。
- 论坛权限：作者本人可改删自己的帖子/评论；`forum:moderate` 可管理全部内容；分类停用隐藏帖子；并发评论计数通过帖子行锁保证。
- 学期与选课窗口：`DRAFT/ACTIVE/CLOSED` 状态机，选课窗口独立于学期起止日期。
- 课程与排课：创建即主讲、`version` 乐观锁、开放/关闭/停开、可选课程带不可选原因；排课校验本课程与授课教师的时间冲突，支持单双周；`GET /schedules/me` 返回学生已选与教师任教课程。
- 选课：容量条件更新防超卖、生成列唯一约束防重复、时间冲突检测、按学生行锁串行化、退课后可重选；选课名单仅任课教师与管理员可见。
- 请假：只能对已选课程提交，`PENDING → APPROVED/REJECTED/CANCELLED` 条件更新状态机，教师只能审批自己任教的课程。
- 自动排课（第一阶段后端）：学期排课配置与教学日历（节次作息、特殊周、节假日/调休/补课）、校区通行时间、教室与可用时间、教师/班级档案与时间偏好、课程库、教学班（合班/选修抽取）、教学任务（含 CSV 导入导出与学时一致性预检）、时间规则与软约束权重。
- 自动排课求解：Google OR-Tools **CP-SAT**（9.15），候选预过滤 + 教师/教室/班级冲突 + 每日/每周/连续节数上限 + 跨校区通行 + 固定/禁排/锁定 + 12 类软约束加权目标；求解结果必须通过**独立复核器**（不依赖模型）才能落库。
- 排课前端工作台：`/scheduling`（课表网格、评分与冲突、点击调整/锁定、排课任务与日志、基础数据录入：课程库/校区教室/教师档案/教学班/教学任务，含 CSV 导入导出与预检）。
- 排课任务：异步执行（受限线程池、可取消、进度/日志/诊断）、9 种业务状态与 CP-SAT 原始状态分离、无解时给出候选阻挡统计与资源紧张度；课表版本化（DRAFT/PUBLISHED/ARCHIVED，每学期仅一个已发布）、人工调整与冲突拦截、锁定、局部重排、版本对比、发布前强制复核、CSV 导出、教师/学生本人课表。
- AI 工具调用：19 个工具（知识库/课程/选课/请假/论坛），工具目录按权限过滤；只读工具调用即执行，写操作工具先生成待确认记录（含目标与影响、10 分钟有效期），确认后才执行；并发确认只执行一次，全部调用写 `source=AI_TOOL` 审计。

## 文档

| 文档 | 内容 |
|---|---|
| [01-development-plan.md](docs/01-development-plan.md) | 阶段计划、完成情况与验证记录 |
| [02-architecture.md](docs/02-architecture.md) | 架构、模块划分、依赖兼容性、安全模型、技术决策与业务假设 |
| [03-database-design.md](docs/03-database-design.md) | 数据库约定、ER 图、已实现与规划中的表 |
| [04-api-design.md](docs/04-api-design.md) | API 规范、错误码、已实现与规划接口、AI 工具映射 |
| [05-project-memory.md](docs/05-project-memory.md) | 项目记忆：当前状态、关键决策、运行/测试环境与下一步 |
| [06-deployment.md](docs/06-deployment.md) | 部署与运维：环境变量、数据库初始化与升级、Nginx/HTTPS、日志审计、备份恢复、冒烟与故障排查 |
| [07-scheduling.md](docs/07-scheduling.md) | 自动排课：现状、概念、业务流程、CP-SAT 模型、约束清单、代码结构、差异与限制 |
| [08-scheduling-api.md](docs/08-scheduling-api.md) | 自动排课 API：权限、状态机、51 个接口、curl 示例、错误与边界行为 |
| [09-scheduling-database.md](docs/09-scheduling-database.md) | 自动排课数据库：25 张表、字段、索引、事务与迁移 |
| [10-scheduling-test.md](docs/10-scheduling-test.md) | 自动排课测试：12 个算法场景 + 5 个集成场景与运行方法 |
| [11-scheduling-deployment.md](docs/11-scheduling-deployment.md) | 自动排课部署：OR-Tools 依赖、配置项、容量规划、运维与已知限制 |
| [12-debian12-install.md](docs/12-debian12-install.md) | **从零部署教程（Debian 12 / Linux 优先）**：系统准备、MySQL 8、构建、systemd、Nginx、HTTPS、升级、备份、故障排查 |
| [openapi-scheduling.json](docs/openapi-scheduling.json) | 排课模块 OpenAPI 3.0.1（由 `/v3/api-docs` 导出） |


## 环境要求

- JDK 17
- Maven 3.9+（仅构建需要）
- MySQL 8.0 或 8.4（需支持 InnoDB ngram 全文解析器，官方发行版默认支持；**不支持 MariaDB**）
- Node.js ≥ 22.12（仅构建前端需要）

## 快速开始

1. 创建数据库和账号（以 MySQL root 执行，把密码换成你自己的）：

   ```sql
   CREATE DATABASE campus_ai CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
   CREATE USER 'campus'@'%' IDENTIFIED BY '<数据库密码>';
   GRANT ALL ON campus_ai.* TO 'campus'@'%';
   ```

   表结构由 Flyway 在应用启动时自动创建，无需手动执行 SQL。

2. 配置环境变量（完整清单见 [.env.example](.env.example)）。最少需要：

   ```bash
   export DB_PASSWORD='<数据库密码>'
   export APP_JWT_SECRET="$(openssl rand -base64 48)"
   # 首次启动时创建初始管理员（至少 12 位），创建成功后请移除该变量
   export APP_BOOTSTRAP_ADMIN_PASSWORD='<初始管理员密码>'
   ```

   Windows PowerShell 写法：`$env:DB_PASSWORD='...'`。也可以复制 `config/application-local.yml.example` 为 `config/application-local.yml`（已被 git 忽略）并在其中配置。

3. 启动：

   ```bash
   mvn spring-boot:run
   # 或
   mvn -DskipTests package && java -jar target/campus-ai-platform-0.1.0-SNAPSHOT.jar
   ```

4. 验证：

   ```bash
   curl http://localhost:8080/actuator/health        # {"status":"UP"}
   curl http://localhost:8080/api/v1/system/info

   # 用启动时设置的管理员密码登录，拿到 accessToken
   curl -s -X POST http://localhost:8080/api/v1/auth/login \
     -H 'Content-Type: application/json' \
     -d "{\"username\":\"admin\",\"password\":\"$APP_BOOTSTRAP_ADMIN_PASSWORD\"}"

   # 把 <token> 换成上一步返回的 data.accessToken
   curl -H 'Authorization: Bearer <token>' http://localhost:8080/api/v1/auth/me
   curl -H 'Authorization: Bearer <token>' http://localhost:8080/api/v1/org-units/tree
   ```

   接口文档：<http://localhost:8080/swagger-ui.html>（可设置 `APP_SWAGGER_ENABLED=false` 关闭）。

   默认管理员用户名为 `admin`（可用 `APP_BOOTSTRAP_ADMIN_USERNAME` 修改）。首次登录后请立即修改密码，并从运行环境移除 `APP_BOOTSTRAP_ADMIN_PASSWORD`。

## 测试

```bash
# 单元测试（不依赖数据库）：80 个
mvn test

# 单元测试 + 集成测试（需要可访问的 MySQL 8 测试库，会清空并重建 campus_ai_test）
# 共 226 个：80 单元 + 146 集成
mvn verify -Pit
```

集成测试默认连接 `localhost:3306/campus_ai_test`，账号为 `campus` / `campus_dev_pwd`，可以通过 `TEST_DB_URL`、`TEST_DB_USERNAME`、`TEST_DB_PASSWORD` 覆盖。测试库专用，**不要指向任何有数据的库**。准备测试库：

```sql
CREATE DATABASE campus_ai_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
GRANT ALL ON campus_ai_test.* TO 'campus'@'%';
```

集成测试覆盖：登录与锁定/限流/防枚举、退出与改密、用户管理与保护规则、乐观锁并发、组织树层级/环/启停、`DataScopeService`、三种角色访问矩阵、知识库空间/成员/文档/版本/分块/全文索引/语料导入、关键词检索（标题加权/去重/权限）、AI 问答（引用/Token/无依据/无模型）、论坛分类/帖子/两级评论/软删除/置顶/隐藏恢复/关键词检索/并发计数、课程与排课/选课防超卖/请假审批状态机、AI 工具目录与两阶段确认、跨模块全链路与混合并发、故障错误映射、审计与安全基础。测试 JVM 会在 `pom.xml` 中启用 `-Djdk.attach.allowAttachSelf=true`，以兼容禁止外部进程自附加的沙箱/CI 环境。

## 本地知识库语料导入（可选）

默认关闭。需要把已有的 Markdown 文档站（例如 SJTU Wiki）导入知识库时，在**首次启动**或需要增量更新时设置：

```bash
export APP_KB_IMPORT_ENABLED=true
export APP_KB_IMPORT_DIR="/path/to/markdown-root"
export APP_KB_IMPORT_OWNER=admin
# 可选：默认均为 SJTU Wiki
export APP_KB_IMPORT_CATEGORY="SJTU Wiki"
export APP_KB_IMPORT_SPACE="SJTU Wiki"
```

导入器会创建（或复用）分类和公开知识库，跳过 `.vitepress` 与空页面，按“相对路径 + 内容哈希”幂等导入：
- 新文件：创建文档和版本 1；
- 内容变化：生成新版本，旧版本块标记 `STALE`；
- 未变化：跳过，不重复写入；
- 单个文件失败：记录错误并继续处理其他文件。

导入完成后建议关闭 `APP_KB_IMPORT_ENABLED` 并重启；生产环境不要开启对任意目录的启动扫描。

## 大模型配置

默认 `AI_CHAT_PROVIDER=none`，不调用任何外部模型。启用时使用 OpenAI 兼容协议：

```bash
export AI_CHAT_PROVIDER=openai
export AI_BASE_URL=https://api.deepseek.com     # 或其他兼容服务
export AI_CHAT_MODEL=deepseek-chat
export AI_API_KEY='<你的 API Key>'
```

- `/api/v1/ai/chat` 只在检索到足够依据时调用模型；检索不足时直接返回 `insufficientEvidence=true`，不编造答案。
- 未配置模型（默认 `none`）或模型调用失败时返回 503 `SERVICE_UNAVAILABLE`，同时保留失败消息，便于排查。
- 检索参数见 `APP_SEARCH_*`，上下文/回答长度见 `APP_AI_*`，完整清单见 [.env.example](.env.example)。
- 当前只实现 MySQL 关键词检索；Embedding/Qdrant 向量检索和混合检索尚未接入，`KnowledgeRetrievalService` 接口已预留。

## 项目结构

```
src/main/java/com/campus/platform
├── common      统一响应、分页、错误码、异常处理、traceId、脱敏、实体基类
├── config      时钟、MyBatis-Plus、OpenAPI、通用配置
├── security    JWT、当前用户、401/403 处理、CORS、密码编码
├── auth        登录、退出、修改本人密码、登录限流
├── user        用户/角色/权限、组织树、DataScopeService、初始管理员引导
├── knowledge   知识库分类/空间/成员、Markdown 文档/版本/分块、语料导入
├── retrieval   关键词检索（MySQL ngram）；Qdrant/混合检索预留
├── ai          AI 问答、上下文预算、引用、会话与 Token；工具注册、调用与两阶段确认
├── forum       论坛分类、帖子、两级评论、版主管理与检索
├── course      学期、课程、授课教师、排课与课表、选课、请假
├── audit       审计日志
├── system      系统信息
└── (ai tool 调用按阶段加入)
src/main/resources/db/migration   Flyway 版本化迁移脚本（V1–V6）
scripts/                         init-db.sql / backup.sh / smoke.sh
```

## 部署

**推荐路径：Linux（Debian 12）** —— 从裸机到可访问站点的完整步骤见
[docs/12-debian12-install.md](docs/12-debian12-install.md)（系统准备 → MySQL 8 → 构建 → systemd → Nginx → HTTPS → 升级/备份/排障）。
Windows 只作为本地试用，见该文附录 A。

其余部署与运维细节（环境变量全量清单、日志审计、备份恢复、冒烟脚本）见 [docs/06-deployment.md](docs/06-deployment.md)。最小步骤：

```bash
# 1) 建库与授权（按需修改库名/账号/密码）
mysql -h 127.0.0.1 -u root -p < scripts/init-db.sql

# 2) 构建并启动（Flyway 自动应用 V1–V6）
mvn clean package -DskipTests
DB_URL='jdbc:mysql://127.0.0.1:3306/campus_ai?characterEncoding=UTF-8' \
DB_USERNAME=campus DB_PASSWORD='***' \
APP_JWT_SECRET="$(openssl rand -base64 48)" \
APP_BOOTSTRAP_ADMIN_PASSWORD='首次启动用的强密码' \
java -jar target/campus-ai-platform-0.1.0-SNAPSHOT.jar

# 3) 冒烟（健康检查/登录/工具目录/只读工具/写工具确认闸门）
BASE_URL=http://127.0.0.1:8080 SMOKE_USERNAME=admin SMOKE_PASSWORD='***' ./scripts/smoke.sh

# 4) 备份
DB_PASSWORD='***' ./scripts/backup.sh /backup/campus_ai.sql.gz
```

## 常见问题

- **启动报“未配置 JWT 签名密钥”**：设置 `APP_JWT_SECRET`（至少 32 字节）。这是有意为之，防止使用弱密钥或默认密钥。
- **启动日志提示“没有可用的管理员账号”**：设置 `APP_BOOTSTRAP_ADMIN_PASSWORD` 后重启。
- **登录返回 401 `AUTH_BAD_CREDENTIALS`**：用户名或密码错误；用户名不存在与密码错误返回相同提示，避免枚举账号。
- **登录返回 403 `AUTH_ACCOUNT_LOCKED`**：连续失败达到阈值（默认 5 次，锁定 15 分钟），或账号被管理员锁定；管理员可在用户详情中解锁。
- **登录返回 429 `TOO_MANY_REQUESTS`**：触发进程内限流，检查响应头 `Retry-After`。多实例部署时每个实例独立计数。
- **AI 问答返回 503 `SERVICE_UNAVAILABLE`**：未配置 `AI_CHAT_PROVIDER=openai` / `AI_API_KEY`，或模型服务不可用；系统不会伪造回答。
- **AI 回答“没有找到足够依据”**：检索结果为空或低于阈值；先确认文档已导入、用户对空间有读权限，再尝试更具体的关键词。
- **Flyway 提示 “MySQL 8.4 is newer than this version of Flyway”**：仅为提示，在 8.4 上已测试通过；阶段二、三验证环境为 MySQL 8.0.46。
- **时间相差 8 小时**：检查 `APP_TIME_ZONE` 与 `DB_SESSION_TIME_ZONE` 是否一致（默认分别为 Asia/Shanghai 和 +08:00）。
