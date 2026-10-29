# 11 自动排课模块部署与配置

> 适用于单实例部署（当前阶段）。多实例与大规模场景的限制见第 7 节。

---

## 1. 依赖

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 17 | `pom.xml` 中 `java.version=17`；OR-Tools 9.15 的 Java 字节码兼容 17+ |
| Maven | 3.9+ | 项目未提供 Maven Wrapper，沿用系统 `mvn` |
| MySQL | 8.0.30+ | 需要生成列（`sch_version.published_key`）与 `utf8mb4_0900_ai_ci` |
| OR-Tools | `com.google.ortools:ortools-java:9.15.6755` | 自带 `win32-x86-64` / `linux-x86-64` / `linux-aarch64` / `darwin-*` 原生库 |
| Spring Boot | 3.4.13 | 与既有模块一致 |

OR-Tools 原生库由 `Loader.loadNativeLibraries()` 在首次求解时解压加载（`SchedulingSolver.ensureNativeLoaded()`），
不需要单独安装；**无需设置 `java.library.path`**。

支持平台：Windows x64、Linux x64/arm64、macOS x64/arm64。
其它平台（如 32 位 JVM、Alpine musl）会抛 `OR-Tools 原生库加载失败`，需要在构建镜像时使用 glibc 基础镜像。

### 1.1 Windows：求解时 JVM 直接崩溃（ACCESS_VIOLATION）

**现象**：点「自动排课」后进程消失，`logs/backend-run.log` 末尾是
`EXCEPTION_ACCESS_VIOLATION ... Problematic frame: C [msvcp140.dll+...]`，
栈里同时出现 `abseil_dll.dll` 与 `jniortools.dll`，`hs_err_pid*.log` 的
`Dynamic libraries` 显示运行库来自 `...\jdk-17\bin\`。

**原因**：Oracle JDK 17 在 `bin` 下自带 `msvcp140.dll` / `vcruntime140*.dll`（14.36）。
Windows 的 DLL 搜索顺序里可执行文件所在目录优先于 `System32`，于是 OR-Tools 9.15 的
`abseil_dll.dll` 会绑到这份旧运行库上，而不是系统里的新版（14.40+），一进 CP-SAT 求解就崩。

**解决**（任选其一，改完重启后端即可）：

```bat
:: 方案 A（本机已采用）：把 JDK 复制一份，只替换 bin 下的 VC 运行库
robocopy "C:\Program Files\Java\jdk-17" "%USERPROFILE%\jdk17-ort" /E
copy /Y C:\Windows\System32\msvcp140.dll      "%USERPROFILE%\jdk17-ort\bin\"
copy /Y C:\Windows\System32\vcruntime140.dll  "%USERPROFILE%\jdk17-ort\bin\"
copy /Y C:\Windows\System32\vcruntime140_1.dll "%USERPROFILE%\jdk17-ort\bin\"
copy /Y C:\Windows\System32\concrt140.dll     "%USERPROFILE%\jdk17-ort\bin\"
:: 之后用 %USERPROFILE%\jdk17-ort\bin\java.exe 启动（deploy\start-backend.cmd 已内置）
```

方案 B（需要管理员权限，一次改掉全机）：把 `C:\Windows\System32` 下的同名 4 个 DLL
覆盖到 `C:\Program Files\Java\jdk-17\bin\`；方案 C：换用不自带 `msvcp140.dll` 的发行版
（如 Eclipse Temurin 17）。

注意：**只预加载** `System.load("...msvcp140.dll")` 不起作用——JDK 自己那份已经先绑定了。

---

## 2. 数据库

```bash
# 1) 建库（若尚未创建）
CREATE DATABASE campus_ai CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

# 2) 启动应用即自动执行 Flyway（V8、V9 为排课模块新增）
#    也可手动：
mvn flyway:info
mvn flyway:migrate
```

新增对象：25 张 `sch_*` 表、5+1 个权限点（`schedule:read`、`schedule:manage`、`schedule:adjust`、
`schedule:publish`、`schedule:preference:self`、`timetable:read`）与 `ACADEMIC_ADMIN`（教务员）角色。
已在已有 V1~V7 数据的库上验证增量升级成功。

验证：

```sql
SELECT version, description FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;
SHOW TABLES LIKE 'sch_%';
SELECT code FROM sys_permission WHERE module='scheduling';
```

---

## 3. 配置项

`application.yml` 新增（均可用环境变量覆盖）：

```yaml
app:
  scheduling:
    solver-threads: ${APP_SCHEDULING_SOLVER_THREADS:2}   # 同时执行的求解任务数
    queue-capacity: ${APP_SCHEDULING_QUEUE_CAPACITY:8}   # 等待队列长度，满则 503
```

求解参数在**每个任务**上配置（`JobCreateRequest`），不是全局配置：

| 参数 | 默认 | 建议 |
|---|---|---|
| `timeLimitSeconds` | 60 | 学院级（数百任务）先用 120~300；观察 `solverStatus` 是否为 OPTIMAL/FEASIBLE |
| `workers` | 4 | 不超过物理核数的一半（求解本身是并行的） |
| `randomSeed` | 1 | 需要复现时固定 seed 且 `workers=1` |
| `allowPartial` | true | 首轮排查建议 true，便于看到未安排原因；正式排课可设 false 强制排满 |
| `keepLocked` | true | 人工固定过的课次保留 |
| `weightOverrides` | 空 | 临时调权，例如 `{"TEACHER_PREFERENCE":15}` |

学期级配置（`PUT /scheduling/terms/{id}/constraints`）持久化在 `sch_constraint_config`，任务级覆盖只对该次求解生效。

### 3.1 容量规划

- 每个求解任务内存占用 ≈ `候选数 × 若干字节` + OR-Tools 模型。候选数受 `max_room_candidates`（默认 12）约束。
- 参考量级：50 个教学任务 × 5 天 × 8 节 × 8 间教室 ≈ 1.6 万候选，模型构建 < 1 秒，求解数十秒内。
- 数百任务时建议把 `max_room_candidates` 降到 6~8，并按学院分批求解（`orgUnitId` 过滤）。

---

## 4. 启动

```bash
# 构建
mvn -DskipTests package

# 运行（数据库与 JWT 密钥按实际环境提供）
DB_URL='jdbc:mysql://127.0.0.1:3306/campus_ai?characterEncoding=UTF-8' \
DB_USERNAME=campus DB_PASSWORD='******' \
APP_JWT_SECRET="$(openssl rand -base64 48)" \
SERVER_PORT=8080 \
java -jar target/campus-ai-platform-0.1.0-SNAPSHOT.jar
```

本地一键启动（MySQL → 后端 → 前端）：`bash scripts/dev-start.sh`。

健康检查与接口文档：

```bash
curl -s localhost:8080/actuator/health
# Swagger UI: http://localhost:8080/swagger-ui/index.html
# OpenAPI JSON: http://localhost:8080/v3/api-docs
```

---

## 5. 初始数据（最小可跑通）

1. 用管理员登录（`APP_BOOTSTRAP_ADMIN_USERNAME/PASSWORD`）。
2. 给教务人员授予 `ACADEMIC_ADMIN` 角色（或直接使用 ADMIN）。
3. 建学期：`POST /api/v1/scheduling/terms`（会自动生成默认 8 节作息）。
4. 建校区、教室；维护教师档案与行政班人数。
5. 建课程库、教学班、教学任务（或 CSV 导入）。
6. `POST /terms/{id}/precheck` 确认无 ERROR。
7. 建并启动排课任务，轮询状态；成功后得到 DRAFT 版本。
8. 复核、调整、发布；教师/学生通过 `/timetables/me` 查看。

完整 curl 示例见 `docs/08-scheduling-api.md` 第 7 节。

---

## 6. 运维

| 事项 | 做法 |
|---|---|
| 日志 | `logs/campus-ai-platform.log`；求解过程另见 `sch_job_log` 与 `GET /jobs/{id}/logs` |
| 审计 | 所有写操作写入 `audit_log`（module=`scheduling`），`GET /api/v1/audit-logs?module=scheduling` |
| 备份 | 排课数据量小（每版本 N 节课时），随库备份即可；`scripts/backup.sh` 已覆盖 |
| 清理 | 历史 DRAFT 版本可归档（`status=ARCHIVED`）而非删除；`sch_assignment` 随版本存在，不建议单独删除 |
| 任务卡住 | `POST /jobs/{id}/cancel` → 若进程异常退出，任务会停留在 RUNNING；重启后由状态巡检（未实现）或手工置 FAILED 处理 |
| 队列满 | 返回 503 `求解队列已满`；调大 `solver-threads` 或错峰执行 |

---

## 7. 已知限制（第一阶段）

1. **单实例互斥**：同一学期“只允许一个任务执行”基于进程内 `ConcurrentHashMap`，多实例部署会失效。
   需要改为数据库锁（`SELECT ... FOR UPDATE`）或引入队列（RabbitMQ/Redis Stream）。
2. **进程崩溃后任务状态**：RUNNING 任务不会自动恢复为 FAILED，需要人工处理或增加启动巡检。
3. **规模**：未在数百任务、上万候选的真实学院数据上压测；约束数量随时段数×教师/班级数增长，
   超大规模建议按学院/年级分批求解（`orgUnitId` 或 `taskIds` 限定范围）。
4. **周次粒度**：课时按“每周重复 + 任务周次范围”建模，不支持同一课次在不同周换时间/教室
   （调休、单双周已支持；跨周差异需拆成多个教学任务）。
5. **学分/学时换算**：一“节”按 1 学时计，未建模小学时（如 0.5 学时）与实验课折算系数。
6. **教学班人数**：选修抽取成员按填写的 studentCount 计；未接入真实选课名单，
   与阶段六 `course_selection` 的联动仅通过 `linked_course_id` 预留。
7. **前端**：第一阶段不提供排课前端，所有能力通过 REST + Swagger UI 验证。
8. **多目标优化**：当前为单目标加权和（未安排惩罚 + 软约束加权），未实现分层/字典序优化；
   权重过大时可能出现“为满足某软约束而牺牲另一个”的取舍，评分明细可用于核对。
