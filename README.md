# 高校 AI 综合服务平台

**把 AI 问答和校园业务拧成一个入口**：问知识库、查课表、选课、请假、发帖、报修投诉，都在同一个对话框里完成。

![version](https://img.shields.io/badge/version-1.1-blue)
![java](https://img.shields.io/badge/Java-17-orange)
![spring](https://img.shields.io/badge/Spring%20Boot-3.4-6DB33F)
![mysql](https://img.shields.io/badge/MySQL-8.0-4479A1)
![license](https://img.shields.io/badge/license-MIT-green)

Java 17 · Spring Boot 3.4 · MyBatis-Plus · MySQL 8 · Spring Security(JWT) · Spring AI · Vue 3 + Vite
模块化单体，35 个控制器、70 张表、15 个 Flyway 迁移。

---

## 核心亮点

| 能力 | 说明 |
|---|---|
| **问答即办事** | 一个输入框先检索知识库（命中直接带出处回答），无依据时自动转给 **21 个业务工具**；写操作先出确认单（含影响范围、10 分钟有效期），确认后才落库 |
| **自动排课** | Google OR-Tools **CP-SAT** 求解：候选预过滤 + 硬冲突约束 + 12 类软约束加权；结果必须通过**独立复核器**（不依赖模型）才允许落库 |
| **权限内建** | 3 个内置角色、**29 个权限点**、`DataScopeService` 组织数据权限，供知识库/课程/请假等模块统一复用 |
| **全链路可审计** | 登录、改密、审批、AI 工具调用全部写 `audit_log`，带 traceId；登录失败锁定 + 限流 + 防账号枚举 |
| **移动端优先** | 左侧导航在手机收成顶部条与侧滑抽屉，表格容器内横滑，正文第一屏可见 |
| **两种部署** | Docker 三容器一条命令起全套；或 Debian 12 裸机 + systemd + Nginx + HTTPS |

## 功能模块

| 模块 | 能力 |
|---|---|
| AI 问答 | 知识库检索增强、引用与 Token 统计、会话记录、工具两阶段确认、21 个工具按权限过滤 |
| 知识库 | 分类/空间/成员授权、Markdown 批量上传与在线编辑、版本、分块、四种可见范围（PUBLIC/ORG/PRIVATE/COURSE）、中文关键词检索 |
| 论坛 | 分类、发帖/两级评论、置顶（限时）、隐藏恢复、版主管理、官方公告广播、互动通知 |
| 课程 | 学期与选课窗口状态机、建课/排课、选课（防超卖、防冲突）、退课、教师课表与学生课表 |
| 自动排课 | 教学日历、教室、教师/班级档案、教学班、教学任务（CSV 导入导出）、课表版本与发布、人工调整、局部重排、CSV 导出 |
| 成绩 | 教师录入草稿→发布、绩点换算、学生 GPA 与加权平均分、学生评教（匿名聚合） |
| 请假与考勤 | 双级审批（任课教师 + 导员，顺序可配）、销假、班级考勤场次、旷课率统计、考勤申诉与缺勤预警 |
| 校务 | 空教室查询、教室借用与审批、校长信箱（投诉建议，仅系统管理员可见可处理） |
| 通知 | 站内信与红点、通知抽屉、分类归档、邮件/微信渠道扩展点（`NotificationChannelSender`） |
| 个人中心 | 资料查看、头像上传、改密码、我发过的帖子 |

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Java 17、Spring Boot 3.4.13、Spring Security + JWT、Spring AI 1.0.3、MyBatis-Plus 3.5.14、Flyway |
| 求解器 | OR-Tools CP-SAT 9.15（自带 win/linux/mac 原生库） |
| 数据库 | MySQL 8.0/8.4（需 ngram 全文解析器，**不支持 MariaDB**） |
| 前端 | Vue 3 + TypeScript + Vite、自研组件、移动端优先 |
| 文档 | springdoc-openapi（`/swagger-ui.html`） |

## 快速开始

**前置**：JDK 17、MySQL 8、Node.js ≥ 22.12（仅构建前端需要）。

```bash
# 1) 建库与账号
mysql -u root -p -e "CREATE DATABASE campus_ai CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
  CREATE USER 'campus'@'%' IDENTIFIED BY '<你的密码>';
  GRANT ALL ON campus_ai.* TO 'campus'@'%';"
# 表结构由 Flyway 启动时自动创建，无需手动执行 SQL

# 2) 配置（三选一：环境变量 / config/application-local.yml / 部署时注入）
cp config/application-local.yml.example config/application-local.yml
# 填 datasource.password 与 app.security.jwt.secret（至少 32 字节）

# 3) 启动后端
mvn -DskipTests package
java -jar target/campus-ai-platform-0.1.0-SNAPSHOT.jar

# 4) 构建并托管前端
cd frontend && npm ci && npm run build && cd ..
node deploy/serve.mjs          # http://127.0.0.1:5173
```

首次启动需设置 `APP_BOOTSTRAP_ADMIN_PASSWORD`（至少 12 位）以创建初始管理员 `admin`，创建成功后请移除该变量。

**验证**：

```bash
curl http://localhost:8080/actuator/health     # {"status":"UP"}
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<密码>"}'
```

接口文档 <http://localhost:8080/swagger-ui.html>，可用 `APP_SWAGGER_ENABLED=false` 关闭。

## 部署

**Docker（最省事）** —— 宿主机只需 Docker，三容器起全套（MySQL + 后端 + Nginx）：

```bash
cp .env.docker.example .env.docker    # 填 DB_PASSWORD / DB_ROOT_PASSWORD / APP_JWT_SECRET
bash scripts/docker-up.sh             # http://localhost:8088
```

离线打包分发用 `bash scripts/docker-build.sh --save`，产物在 `dist/`。

**Debian 12 裸机** —— 从系统准备到 HTTPS 的完整步骤见 [docs/12-debian12-install.md](docs/12-debian12-install.md)（Windows 本地试用见该文附录 A）。

## 关键配置

| 变量 | 说明 |
|---|---|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | 数据源，默认 `jdbc:mysql://localhost:3306/campus_ai` |
| `APP_JWT_SECRET` | JWT 签名密钥，**至少 32 字节，必填**，未配置则拒绝启动 |
| `APP_BOOTSTRAP_ADMIN_PASSWORD` | 首次启动创建管理员，用后移除 |
| `AI_CHAT_PROVIDER` / `AI_API_KEY` / `AI_BASE_URL` / `AI_CHAT_MODEL` | 默认 `none` 不调用外部模型；设为 `openai` 走 OpenAI 兼容协议 |
| `APP_TIME_ZONE` / `DB_SESSION_TIME_ZONE` | 默认 `Asia/Shanghai` / `+08:00`，两者需一致 |

完整清单见 [.env.example](.env.example)。

## 测试

```bash
mvn test              # 单元测试，不依赖数据库
mvn verify -Pit       # 单元 + 集成，需可访问的 MySQL 测试库（会重建 campus_ai_test）
```

共 230+ 个用例，覆盖登录锁定/限流、并发与乐观锁、三角色访问矩阵、知识库检索、选课防超卖、请假状态机、排课算法与复核、AI 工具两阶段确认等。测试库默认 `localhost:3306/campus_ai_test`，可用 `TEST_DB_*` 覆盖，**不要指向有数据的库**。

## 文档

| 分类 | 文档 |
|---|---|
| 总览 | [开发计划](docs/01-development-plan.md) · [架构](docs/02-architecture.md) · [数据库设计](docs/03-database-design.md) · [API 设计](docs/04-api-design.md) · [项目记忆](docs/05-project-memory.md) |
| 部署 | [部署运维](docs/06-deployment.md) · [Debian 12 安装](docs/12-debian12-install.md) · [Docker 部署](docs/13-docker-deploy.md) |
| 自动排课 | [设计](docs/07-scheduling.md) · [API](docs/08-scheduling-api.md) · [数据库](docs/09-scheduling-database.md) · [测试](docs/10-scheduling-test.md) · [部署](docs/11-scheduling-deployment.md) |
| 校务 | [成绩/考勤/请假](docs/14-grades-leave-attendance.md) · [通知/申诉/借用](docs/15-notification-appeal-booking.md) · [论坛通知与公告](docs/16-forum-notify-announcement.md) |

## 常见问题

- **启动报"未配置 JWT 签名密钥"**：设置 `APP_JWT_SECRET`（≥32 字节），这是有意为之，防止弱密钥上线。
- **AI 问答返回 503**：未配置模型或模型服务不可用。系统不会伪造回答，检索不足时直接返回"没有找到足够依据"。
- **登录 401/403/429**：401 账号或密码错（不区分，防枚举）；403 账号锁定（默认 5 次失败锁 15 分钟）；429 触发限流，看 `Retry-After`。
- **时间相差 8 小时**：检查 `APP_TIME_ZONE` 与 `DB_SESSION_TIME_ZONE` 是否一致。
- **Windows 上求解器崩溃（ACCESS_VIOLATION）**：Oracle JDK 17 自带的 `msvcp140.dll` 版本过旧，导致 OR-Tools 的 abseil 绑定到旧运行库。换用系统新运行库的 JDK，详见 [docs/11-scheduling-deployment.md](docs/11-scheduling-deployment.md)。

## 版本

**1.1** —— 校务服务阶段（V10–V15）：成绩与绩点、学生评教、校长信箱、空教室与教室借用、请假双审批与销假、班级考勤与申诉预警；站内通知与公告广播；论坛互动通知与限时置顶；个人资料与头像；Docker 三容器部署；前端导航分组与移动端重构。

**1.0** —— 八阶段基础能力：登录鉴权、用户与组织、知识库与检索、AI 问答与工具调用、论坛、课程与选课、请假、自动排课（CP-SAT）。

## License

MIT
