# 10 自动排课模块测试说明

> 实测结果（2026-10-29，Windows 10 + JDK 17 + MySQL 8.0.46）：
> `mvn verify -Pit` → **226 个测试全部通过**（80 单元 + 146 集成），其中排课算法测试 12 个、排课集成测试 9 个；构建 `BUILD SUCCESS`。

---

## 1. 测试分层

| 层次 | 位置 | 依赖 | 运行方式 |
|---|---|---|---|
| 算法单元测试 | `src/test/java/com/campus/platform/scheduling/solver/SchedulingSolverTest.java` | 纯 Java + OR-Tools 原生库，**不连数据库** | `mvn test`（surefire） |
| 接口集成测试 | `src/test/java/com/campus/platform/SchedulingIT.java` | 真实 MySQL 8 + 完整 Spring 上下文 + MockMvc | `mvn verify -Pit`（failsafe） |
| 回归测试 | 既有 `*IT`（课程、选课、权限、基础迁移等） | 同上 | 同上 |

集成测试基类 `IntegrationTestBase` 在上下文启动时对测试库执行 Flyway `clean + migrate`，
因此每次运行都验证了 **V1→V9 迁移脚本可在空库完整执行**；测试数据使用随机后缀，不依赖全表计数。

---

## 2. 算法测试（12 个）与需求场景对应

| # | 需求场景 | 测试方法 | 断言要点 |
|---|---|---|---|
| 1 | 最小可行排课 | `minimalProblemIsSolvedAndPassesIndependentValidation` | 状态 SUCCEEDED；4 次课全部安排；复核 0 违反 |
| 2 | 教师冲突 | `sameTeacherNeverTeachesTwoCoursesAtOnce` | 同一教师两门课无时间重叠；复核 0 违反 |
| 3 | 教室冲突 | `singleRoomCannotHostTwoCoursesAtTheSameTime` | 单教室下两任务不同时段；全部使用该教室 |
| 4 | 班级冲突 | `sameAdministrativeClassHasNoOverlappingRequiredCourses` | 同班两门必修不重叠 |
| 5 | 教室容量 | `classLargerThanEveryRoomIsReportedAsUnschedulable` | 预检 `NO_CANDIDATE` 且提示容量；求解 PARTIAL；未安排原因含任务 |
| 6 | 教师不可用时间 | `teacherUnavailableSlotIsNeverUsed` | 禁排的周一整日不被使用 |
| 7 | 连堂课 | `doublePeriodCourseStaysInsideOneSection` | 不从第 4 节跨午休连到第 5 节 |
| 8 | 固定课程 | `fixedTimeRuleIsRespected` | FIXED 规则下精确落在周三第 3 节 |
| 9 | 锁定课时 | `lockedPlacementIsKeptWhenRescheduling` | 锁定课次重排后仍在原时间/原教室 |
| 10 | 软约束权重 | `softConstraintWeightChangesTheObjectiveAndTheResult` | PREFERRED 生效（满足率 1.0）；AVOID 生效（避开该日）；评分可解释 |
| 11 | 无解诊断 | `contradictoryFixedAndUnavailableRulesAreReportedAsInfeasibleWithDiagnostics` | 预检报 `NO_CANDIDATE`；状态 INFEASIBLE/PARTIAL；诊断有资源摘要 |
| 12 | 取消与并发 | `cancelledSolveStopsAndReportsCancelled`（算法层）/ `permissionsAndDuplicateRunAreEnforced`（接口层） | 取消后状态 CANCELLED；重复启动 409；越权 403 |

这些测试**不断言唯一课表**（约束多解），只断言硬约束满足、业务状态与评分口径一致。

---

## 3. 集成测试（9 个）

| 测试方法 | 覆盖内容 |
|---|---|
| `solvesValidatesAdjustsAndPublishes` | 建学期→教室→教师/班级档案→课程→教学班→教学任务→预检→创建并启动任务→轮询到 SUCCEEDED→复核 0 违反→冲突移动 409→合法移动 200→锁定后移动 409→check→发布 PUBLISHED→已发布版本调整 409→发布记录→教师课表→学生课表→CSV 导出→复制版本 |
| `inconsistentHoursAreRejectedBeforeSolving` | 总学时改小后预检 `HOURS_CONSISTENCY`，`solvable=false`；启动任务得到 `FAILED` + `PRECHECK_FAILED`；诊断有内容 |
| `classLargerThanRoomsIsReportedWithReason` | 5000 人班级 vs 20 座教室：预检不可行且说明“教室容量不足”；任务 FAILED |
| `permissionsAndDuplicateRunAreEnforced` | 学生建任务 403；学生启动任务 403；重复启动 409；已完成再启动 409；重试生成新 DRAFT；教师建教室 403；未登录 401 |
| `lockedEntriesSurviveReschedule` | 锁定课次后以该版本为基准全量重排，新版本仍保留该课次的原时间 |
| `rescheduleCreatesANewVersionThatActuallyRuns` | 回归：局部重排接口返回的任务必须真的执行完（修复前因事务未提交，任务永远停在 QUEUED） |
| `importCsvAcceptsExcelBomAndQuotedHeader` | 回归：Excel 另存的 CSV（UTF-8 BOM + 引号字段）能正确识别表头并导入 |
| `disabledTaskStillCountsInVersionEvaluation` | 回归：教学任务停用后，既有版本仍完整参与复核（2 次课、0 冲突），不再报“数据不完整” |
| `publishedTimetableBlocksCalendarChangesAndCheckNeedsAdjustPermission` | 回归：已发布学期禁止改配置/作息/日历；`check` 需要 `schedule:adjust`（只读账号 403） |

集成测试同时校验**数据库结果**（版本状态、发布记录、课时行、锁定标记）而不只看 HTTP 状态码。

---

## 4. 运行方法

### 4.1 前置条件

- JDK 17（本机使用 `.localrun/jdk-17.0.20.1+1`）
- MySQL 8，测试库 `campus_ai_test`（字符集 `utf8mb4`）
- 测试账号需有建库/建表权限

```bash
# 建测试库（只需一次）
mysql -h127.0.0.1 -ucampus -p -e \
  "CREATE DATABASE IF NOT EXISTS campus_ai_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
```

### 4.2 命令

```bash
# 只跑算法测试（最快，不连库）
mvn test -Dtest=SchedulingSolverTest

# 只跑排课集成测试
TEST_DB_PASSWORD=****** mvn verify -Pit -Dit.test=SchedulingIT

# 全量回归（单元 + 集成）
TEST_DB_PASSWORD=****** mvn verify -Pit
```

数据库连接可用环境变量覆盖：`TEST_DB_URL`、`TEST_DB_USERNAME`、`TEST_DB_PASSWORD`。
Windows 下若 `mvn` 默认 JDK 不是 17，先设置 `JAVA_HOME`。

### 4.3 注意事项

- 集成测试会 **clean 测试库**（`application-test.yml` 中 `clean-disabled=false`），不要指向有数据的库。
- 排课求解在线程池中异步执行，测试用 500ms 轮询 + 90 秒上限等待终态。
- OR-Tools 原生库首次加载约 1~2 秒；`windows-x86-64`、`linux-x86-64`、`macos` 均由依赖自带。
- 集成测试共享同一个 Spring 上下文与数据库，教室是全局资源，因此容量类用例使用极端人数（5000）避免被其它用例创建的教室“救活”。

---

## 5. 已验证 / 未验证

| 项 | 状态 |
|---|---|
| V1→V9 在空库执行 | ✅ 集成测试上下文每次执行 |
| V1→V7 旧库增量升级到 V9 | ✅ 在本地开发库（已有 V1~V7 数据）实际执行成功 |
| CP-SAT 在 Windows + JDK 17 运行 | ✅ 算法测试与接口测试均实际求解 |
| 求解结果经独立复核器验证 | ✅ 算法测试与集成测试都断言 `hardViolations=0` |
| 权限（403/401）、重复启动、发布冲突 | ✅ 集成测试覆盖 |
| 多实例并发（跨进程） | ❌ 未覆盖：当前互斥基于进程内状态，见“已知限制” |
| 万人级规模性能 | ❌ 未压测：算法测试规模为 2~4 个任务，真实学院级（数百任务）需按第 11 节调参 |
| 前端排课页 | ✅ Playwright 冒烟：桌面/390px 无横向溢出、0 控制台错误；课表 4 块、评分 75.4、候选项 96 个；点选候选后课表与数据库同步变更 |
| 前端基础数据 | ✅ Playwright 冒烟：课程新建后列表可见；教室/教师/教学班/教学任务四个分区均能加载；教学任务 新建（含指定教室）→ 编辑回填 → 删除 全链路通过，控制台 0 错误 |
| 前端排课设置 | ✅ Playwright 冒烟：5 个分区加载正常（10 条作息 / 12 项软约束 / 6 个规则模板）；学期参数、教学日历、约束权重保存均返回成功；用「午休禁排」模板新建规则后列表 2→3、删除后回到 2；已发布学期改学期参数返回 409 并有可读提示 |
