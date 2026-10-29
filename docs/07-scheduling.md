# 07 自动排课模块（scheduling）

> 本文档是自动排课模块的需求、设计、进度与使用说明的唯一入口。开发可能随时中断，**“0. 开发进度”一节始终反映真实状态**，只记录实际编译/测试过的内容。

## 0. 开发进度

最后更新：2026-10-29

| 步骤 | 内容 | 状态 |
|---|---|---|
| S0 | 现状检查（JDK/Boot/ORM/实体/权限/响应格式/迁移/测试） | ✅ 完成，结论见第 1 节 |
| S1 | 引入 OR-Tools `ortools-java 9.15.6755`，在 JDK 17 + Linux x86-64 上实测 CP-SAT 求解 | ✅ 完成 |
| S2 | 设计文档（本文件） | ✅ 第 1–5 节完成，其余随开发补充 |
| S3 | `V8__init_scheduling.sql` 迁移脚本（23 张 `sch_*` 表 + 5 个权限点 + 教务员角色） | ✅ 完成；已在 MySQL 8.4.10 空库上按 V1→V8 顺序执行通过 |
| S4 | 求解层（纯 Java + CP-SAT）与算法测试 | ✅ 12 个算法测试通过（Windows + JDK 17 实测求解） |
| S5 | 基础数据管理接口（学期/日历/教室/教师/班级/课程/教学班/任务/规则） | ✅ 51 个接口，见 08 文档 |
| S6 | 排课任务、版本、调整、发布、导出、课表查询 | ✅ 异步任务、版本对比、局部重排、发布与 CSV 导出 |
| S7 | 集成测试、全量回归、API 文档与调试示例 | ✅ 9 个排课集成测试；全量 226 个测试通过；文档 08~11 |
| S8 | 前端排课工作台（课表/评分/调整/任务/预检） | ✅ `/scheduling` 页面，桌面与 390px 移动端实测无横向溢出 |

> 交付文档索引：
> - 接口：`docs/08-scheduling-api.md`（含 curl 示例与错误码）
> - 数据库：`docs/09-scheduling-database.md`
> - 测试：`docs/10-scheduling-test.md`
> - 部署：`docs/11-scheduling-deployment.md`
> - 机器可读规范：`docs/openapi-scheduling.json`（由 `/v3/api-docs` 导出）

恢复开发时：先读本节与第 9 节“已知限制”，再按第 10 节命令构建验证。

## 1. 现状检查结论（2026-10-29）

| 项 | 实际情况 | 对排课模块的影响 |
|---|---|---|
| JDK / 构建 | Java 17，Maven（**无 Maven Wrapper**），Spring Boot 3.4.13 | 不新增 wrapper；构建命令沿用 `mvn` |
| 持久化 | MySQL 8 + MyBatis-Plus 3.5.14 + Flyway，已发布 V1–V7 | 新增 `V8__init_scheduling.sql`，不修改旧脚本 |
| 认证权限 | 无状态 JWT，每请求从库加载角色/权限；`@PreAuthorize` + Service 层 `Permissions.require` 双层 | 新增权限点 `schedule:*`、`timetable:read` 与“教务员”角色 |
| 响应/异常 | `ApiResponse{success,code,message,data,errors,traceId}`；`BusinessException(ErrorCode)` 统一映射 | 新增 `SCH_*` 错误码 |
| 已有实体 | `sys_user`（教师/学生，角色区分）、`sys_org_unit`（COLLEGE/DEPARTMENT/MAJOR/CLASS）、`semester`、`course`（学期内开课+选课）、`course_schedule`（手工排课） | 复用 `semester`、`sys_user`、`sys_org_unit(CLASS)` 作为学期/教师/行政班；排课专属数据放在 `sch_*` 扩展表 |
| 已有排课 | 阶段六的 `course_schedule` 只是课程的手工时间段，没有教室资源、班级、教学任务概念 | 不改动；自动排课结果独立存放在版本化的 `sch_assignment` 中 |
| 测试 | 单元测试 `*Test`（surefire），集成测试 `*IT`（failsafe，`-Pit`，真实 MySQL，clean+migrate） | 排课算法测试为纯单元测试（不需要数据库），接口为 IT |

## 2. 概念与边界

| 概念 | 存储 | 说明 |
|---|---|---|
| 学期 | `semester`（已有）+ `sch_semester_config` | 教学周数、教学日、第 1 周周一 |
| 教学日历 | `sch_period`、`sch_calendar_week`、`sch_calendar_date` | 节次/作息、特殊教学周、节假日/调休/停课 |
| 课程 | `sch_course` | 课程库（与学期无关）：代码、名称、类型、学分、学时、默认教室要求 |
| 行政班 | `sys_org_unit`(type=CLASS) + `sch_class_profile` | 人数、年级、日最大节数、连续上课上限 |
| 教学班 | `sch_teaching_class` + `sch_teaching_class_member` | 实际上课的学生集合；可由多个行政班合班（WHOLE）或选修抽取（ELECTIVE） |
| 教学任务 | `sch_task` + `sch_task_teacher` | 某学期某课程由某些教师面向某教学班开设：周次、每周次数、单次节数、教室要求 |
| 课时安排 | `sch_assignment` | 教学任务第 k 次课（每周重复）在某版本中的星期/起始节/教室 |
| 排课任务 | `sch_job` | 一次求解过程：参数、输入快照、状态、日志、指标 |
| 排课版本 | `sch_version` | 一份完整课表：可调整、比较、发布；每学期最多一个 PUBLISHED |

学时口径：一“节”= 1 学时。任务提供学时 = 单次节数 × 每周次数 × 有效教学周数（任务周次范围 ∩ 单双周 − 停课/考试周）。

## 3. 业务流程

1. 管理员建学期（已有 `/api/v1/semesters`），再配置排课日历：`PUT /scheduling/semesters/{id}/config`（教学周数、教学日、每日节数）、节次作息、特殊周、特殊日期（节假日/调休/停课/补课）。
2. 维护基础数据：校区与通行时间、教室、教师排课档案（工作量上限、可授课程）、行政班档案（人数、日上限）、课程库、教学班（合班/选修抽取）、教学任务（可 CSV 批量导入/导出）。
3. 维护时间规则：教师/班级/教室/课程/任务/全局的禁排（FORBIDDEN，硬）、回避（AVOID，软）、偏好（PREFERRED，软）、固定（FIXED，硬）；教师本人可维护自己的偏好与回避。
4. 维护约束配置：每学期覆盖软约束权重与开关，硬约束不可关闭（个别带参数的除外，见第 5 节）。
5. 预检 `POST /scheduling/semesters/{id}/precheck`：学时一致性、候选为空、工作量超限等问题在求解前给出。
6. 创建并启动排课任务（`sch_job`）：异步执行 CP-SAT，期间可查询进度、取消。
7. 成功后生成 DRAFT 版本（`sch_version`）：包含课时安排、未安排清单与原因、硬约束复核结果、软约束得分。
8. 人工调整：移动、放置、取消安排、锁定/解锁；每次调整都经硬约束校验，冲突时拒绝并列出冲突；可查询某课时的可用时段。
9. 重新计算：以某版本为基础全量重排（尽量少改动）或局部重排（只放开指定任务，其余固定）；版本对比。
10. 发布：硬约束复核通过才能发布，同学期旧发布版本自动归档；可撤销发布。
11. 教师、学生通过 `GET /api/v1/timetables/me` 查看已发布课表；按班级/教师/教室查看；导出 CSV。

## 4. 求解模型（CP-SAT）

### 4.1 决策变量
- **候选组合**：对每个教学任务 t 生成候选 c = (星期 d, 起始节 p, 教室 r)，满足：d 是教学日；[p, p+len−1] 不跨作息段（上午/下午/晚上，午休不可连堂）；不落入任何相关 FORBIDDEN 规则（按周次求交，规则周次与任务周次不相交则不影响）；存在 FIXED 规则时必须完全落在某个固定窗口内；教室类型、设备、容量、指定/禁用、开放时段满足。
- 布尔变量 `x[t,c]`：任务 t 在候选 c 上排一次课（每周重复）。
- 同一任务的多次课是可互换的，因此不为“第 k 次课”单独建变量，而是约束 `Σc x[t,c] = n_t − u_t`，`u_t ∈ [0, n_t]` 为未安排次数。这样既消除了会话间的对称性，也让变量数下降 n_t 倍。结果映射时按 (星期, 节次) 排序得到第 1..n 次课。
- 不允许部分安排时 `u_t = 0`；锁定的课时直接把对应 `x` 固定为 1（若锁定位置已不合法，求解前就报错）。
- 教室候选：只保留满足硬条件的教室，并按“容量最贴合”取前 K 个（默认 K=12，可配置），指定/偏好教室总是保留。这是避免 时间×教室 笛卡尔积膨胀的主要手段。不需要教室的任务（如校外实践）不生成教室维度。

### 4.2 资源冲突（按周次精确处理）
资源 = 教师、教室、行政班、教学班。两个任务只有在周次（含单双周、停课周）有交集时才会冲突。对资源 R 的任务集合，逐周计算“当周活动任务集合”，去重并去掉被包含的子集，只对极大集合 S 建约束：
- 对每个 (d, p)：`Σ_{t∈S, c 覆盖 (d,p)} x[t,c] ≤ 1`。
- 行政班的**选修抽取**成员（只有部分学生）：不同选修任务之间允许同时段（学生只选其一），但与该班的整班任务冲突：`Σ整班 + Σ某一选修任务 ≤ 1`。

### 4.3 其他硬约束
教师每日/每周最大节数、每日最多课次数、最大连续节数（在作息段内的滑动窗口）；行政班每日最大节数、最大连续节数；同一任务一天最多一次（任务可放开）；统一起始节（任务可要求每次课同一节次）；不同校区之间的通行时间（相邻两次课间隔不足则不能分属不同校区，对教师和行政班都生效）。

### 4.4 目标函数（最小化）
`10000 × 未安排次数 + Σ 软约束权重 × 违反量`，软约束见第 5 节。所有系数为整数。

### 4.5 复核与评分（与求解器无关）
`SchedulingValidator` 用同一份问题数据独立复核每条硬约束；`SchedulingEvaluator` 独立计算每条软约束的满足率与扣分。求解结果、人工调整、发布前都会走这两步，求解器建模错误不会被静默带入课表。

### 4.6 未安排原因
- 求解前：候选为空时给出逐项过滤统计（如“40 个时段中 12 个因教师禁排排除；满足 COMPUTER_LAB 且容量 ≥120 的教室为 0，最大容量 60”）。
- 求解后：对每个未安排的课次，把它逐一试放到每个候选上，用复核器统计阻挡原因（如“120 个候选：教师冲突 60、班级冲突 40、教室冲突 20”）。

## 5. 约束清单

代码定义在 `scheduling.solver.ConstraintCode`。硬约束不可关闭（数值上限来自档案，为空即不限制）；软约束的 `enabled/weight` 可按学期配置（`sch_constraint_config`），也可在单次排课任务中覆盖。

### 5.1 硬约束

| 代码 | 含义 | 实现位置 |
|---|---|---|
| TEACHER_CONFLICT | 同一教师同一时段只能上一门课（含共同授课的全部教师） | 资源约束 |
| ROOM_CONFLICT | 同一教室同一时段只能一门课 | 资源约束 |
| CLASS_CONFLICT | 同一行政班/教学班同一时段只能一门课（选修抽取规则见 4.2） | 资源约束 |
| TEACHER_UNAVAILABLE | 教师 FORBIDDEN 时段 | 候选过滤 |
| ROOM_UNAVAILABLE | 教室维护/不开放（FORBIDDEN）、教室停用 | 候选过滤 |
| CLASS_FORBIDDEN | 行政班/教学班 FORBIDDEN 时段 | 候选过滤 |
| COURSE_FORBIDDEN | 课程、任务、全局 FORBIDDEN 时段（如午休、周会） | 候选过滤 |
| ROOM_CAPACITY | 教室容量 ≥ 教学班人数 | 候选过滤 |
| ROOM_TYPE | 教室类型匹配（未指定时只用普通/多媒体教室） | 候选过滤 |
| ROOM_FEATURES | 教室具备任务要求的全部设备标签 | 候选过滤 |
| ROOM_ASSIGNMENT | 指定教室（REQUIRED）只能用它们，禁用教室（FORBIDDEN）不用 | 候选过滤 |
| HOURS_CONSISTENCY | 总学时 = 单次节数 × 每周次数 × 有效教学周数 | 预检，不一致的任务不参与求解并报告 |
| TEACHING_TIME | 只在教学日、有效节次内；连堂不跨作息段 | 候选过滤 |
| FIXED_TIME | 存在 FIXED 规则时课次必须落在固定窗口内 | 候选过滤 |
| LOCKED | 已锁定课时保持原位 | 变量固定 |
| SESSION_COUNT | 每任务每周安排次数恰为要求次数（允许部分安排时缺的次数计入未安排并报告） | 等式约束 |
| SAME_DAY | 同一任务同一天最多一次（任务 `allowSameDay=true` 可放开） | 线性约束 |
| UNIFORM_START | 任务要求 `uniformStart` 时每次课起始节相同 | 线性约束 |
| TEACHER_DAILY_MAX / TEACHER_WEEKLY_MAX | 教师每天/每周最大节数 | 线性约束（按周次极大集合） |
| TEACHER_DAILY_SESSIONS | 教师每天最多课次数 | 线性约束 |
| TEACHER_MAX_CONSECUTIVE / CLASS_MAX_CONSECUTIVE | 最大连续节数（作息段内滑动窗口） | 线性约束 |
| CLASS_DAILY_MAX | 行政班每天最大节数 | 线性约束 |
| CAMPUS_TRAVEL | 相邻课次间隔小于校区通行时间时不能分属不同校区 | 线性约束 |

### 5.2 软约束

| 代码 | 默认权重 | 违反量 |
|---|---|---|
| TEACHER_PREFERENCE | 10 | 教师有 PREFERRED 规则时，课次不在任何偏好窗口内；落在 AVOID 规则内（按规则权重倍数） |
| CLASS_PREFERENCE | 8 | 行政班/教学班 AVOID / PREFERRED |
| COURSE_PREFERENCE | 8 | 课程/任务 AVOID / PREFERRED |
| GLOBAL_AVOID | 5 | 全局 AVOID（如晚上、周五下午） |
| SESSION_SPREAD | 5 | 同一任务两次课在相邻两天 |
| CLASS_DAILY_BALANCE | 3 | 行政班每周最忙一天的节数（越均衡越好） |
| TEACHER_COMPACT | 2 | 教师到校天数 |
| TEACHER_HALF_DAY | 3 | 教师占用到的「星期 × 半天（上午/下午/晚上）」格子数；越小说明同一教师的课越集中在一个半天 |
| ROOM_STABILITY | 3 | 同一任务使用的教室数 − 1 |
| ROOM_FIT | 1 | 空座率（每 10% 计 1） |
| PRIORITY_PRIME_TIME | 2 | 优先级 × 非上午课次 |
| HOLIDAY_LOSS | 4 | 因节假日/停课损失的课次（已扣除调休补课） |
| MINIMIZE_CHANGES | 20 | 重排时相对基准版本改变时间的课次（换教室计 1/4） |

评分：每条软约束给出 `applicable/violated/penalty/satisfaction`；总分 = 100 × (1 − 未安排课次/总课次) × 加权满足率，其中加权满足率 = Σ(权重×满足率)/Σ权重（仅统计适用项）。硬约束违反数 > 0 的版本不能发布。

## 6. 代码结构

```
com.campus.platform.scheduling
├── controller/            REST 入口（5 个控制器，51 个端点）
│   ├── SchedulingTermController      学期、作息、日历、约束、预检
│   ├── SchedulingCatalogController   校区、教室、教师、班级、课程、教学班、规则
│   ├── TeachingTaskController        教学任务 CRUD / CSV 导入导出 / 预检
│   ├── SchedulingJobController       排课任务：创建/启动/取消/重试/状态/日志/诊断
│   └── ScheduleController            版本、课表、调整、局部重排、发布、导出、本人课表
├── service/
│   ├── TermCalendarService           学期配置、节次、特殊周/日期、约束权重
│   ├── ResourceCatalogService        教室/教师/班级档案与可用时间、工作量
│   ├── CourseCatalogService          课程库、教学班
│   ├── TeachingTaskService           教学任务、CSV、预检
│   ├── RuleService                   时间规则与模板
│   ├── SchedulingProblemLoader       从库装配 SchedulingProblem（批量查询、日历位图）
│   ├── SchedulingJobService          异步执行、线程池、取消、进度、日志、落库
│   ├── ScheduleQueryService          版本、课表网格、未安排、评分、对比、导出、本人课表
│   ├── ScheduleAdjustService         移动/取消/锁定/候选位置/复核/局部重排
│   └── SchedulePublishService        发布、撤销、发布记录
├── solver/               纯 Java 求解层（不依赖 Spring，可单测）
│   ├── CandidateGenerator            候选生成 + 过滤统计（未安排原因）
│   ├── SchedulingModelBuilder        CP-SAT 变量、硬约束、目标函数
│   ├── SchedulingSolver              求解、取消、进度、结果映射
│   ├── SchedulingValidator           独立硬约束复核（不依赖模型）
│   ├── SchedulingEvaluator           软约束评分（可解释）
│   └── SchedulingPrecheck            预检、未安排原因、无解诊断
├── entity/ mapper/       25 个实体与 MyBatis-Plus Mapper
└── dto/                  5 组 DTO（按领域聚合，均为 record）

frontend/src
├── views/scheduling/SchedulingView.vue        排课工作台（学期/版本/视角 + 三个页签）
└── components/scheduling/
    ├── ScheduleGrid.vue       课表网格（跨节渲染、锁定标记、点击调整）
    ├── JobPanel.vue           排课任务：创建/启动/轮询/取消/日志
    ├── AdjustDialog.vue       课时调整：按“时段 + 教室”分组的候选项
    ├── BaseDataPanel.vue      基础数据容器（5 个分区，按建库顺序排列）
    ├── SettingsPanel.vue      排课设置：学期参数/节次作息/教学日历/约束权重/时间规则
    ├── RulePanel.vue          时间规则 CRUD + 模板
    ├── CoursePanel.vue        课程库 CRUD
    ├── CampusRoomPanel.vue    校区与教室 CRUD
    ├── TeacherPanel.vue       教师排课档案与可授课程
    ├── TeachingClassPanel.vue 教学班（整班/合班/选修抽取）
    └── TaskPanel.vue          教学任务 CRUD + 指定教室 + 预检 + CSV 导入导出
```

关键设计：**求解器只产出候选**，落库前必须通过 `SchedulingValidator` 的独立复核；
评分由 `SchedulingEvaluator` 独立计算，二者与求解器共用同一份 `SchedulingProblem`，口径一致但实现分离。

## 7. 与需求文档的差异（有意调整）

| 需求 | 实际实现 | 原因 |
|---|---|---|
| `POST /api/v1/scheduling/tasks` 同时指“教学任务”和“排课任务” | 教学任务 `/teaching-tasks`，排课任务 `/jobs` | 需求第 4.7 与第 7 节路径冲突，且二者是不同实体，混用会造成歧义 |
| `POST /terms` 创建学期 | 复用 `semester` 表，同一接口创建学期 + 排课配置 | 系统已有学期实体，重复建表会分裂数据 |
| `sch_classroom` | `sch_room` | 与既有命名习惯一致（room 表示教学场地，含实验室/机房） |
| 独立“排课任务日志表” | `sch_job.log_text` + V9 的 `sch_job_log` | 前者保留完整 CP-SAT 日志，后者支持接口分页查看关键节点 |
| 冲突记录表 `schedule_conflict_record` | `sch_version_issue` | 冲突与未安排原因同属“版本问题”，合表便于整体重写与查询 |
| `SCHEDULING_VIEW` 等大写权限 | `schedule:read/manage/adjust/publish/preference:self`、`timetable:read` | 沿用项目既有 `module:action` 命名 |
| `openapi.yaml` | `docs/openapi-scheduling.json` + 运行时 `/v3/api-docs`、Swagger UI | 从运行实例导出，保证与实际实现一致（116 个 schema、51 个路径） |

## 8. 前端排课工作台

路由 `/scheduling`（导航「排课」，`schedule:read` 或 `timetable:read` 可见）：

- **课表页签**：学期 → 版本 → 视角（全部/教师/班级）；网格按节次跨行渲染，锁定课次带「锁」标记；
  右侧固定显示评分、已排课次、硬约束冲突与扣分项；点击课程块打开调整弹窗。
- 课程块按视角显示「另一个维度」：教师视角显示**班级**（一个老师教多个班时必须能区分这一节上给谁）、
  隐藏重复的教师名；班级视角显示**教师**、隐藏班级；全部视角两者都显示。悬停可看完整信息（班级/教师/教室/周次）。
- **调整弹窗**：候选位置按“星期 + 节次”分组、每间教室一个按钮，标注「当前」；支持锁定/解锁与取消安排；
  冲突由后端判定并返回 409，前端直接展示原因。
- **排课任务页签**：设置时限、线程、是否允许部分安排与保留锁定，一键创建并启动；2 秒轮询进度，
  可取消、查看日志、跳转到结果版本。
- **基础数据页签**（排课的前置输入，顺序即建库顺序）：
  - **课程库**：新建/编辑/停用课程；学分、总学时、课程类型、默认教室类型、必需设备、优先级。
  - **校区与教室**：校区卡片筛选 + 就地新建/改名；教室新建/编辑（编号、楼栋、楼层、容量、类型、设备标签）。
  - **教师档案**：教师来自「管理 → 用户」（角色 TEACHER），这里维护周/日节数上限、连续上课上限、可授课程。
  - **教学班**：整班参加、多班合班、按选修人数抽取；人数按班级档案汇总，可显式覆盖。
  - **教学任务**：新建/编辑/删除、教师多选、指定教室（只能用/优先用/不能用）、学时一致性提示、
    一键预检、CSV 导入与导出（导出可直接当模板）。
- **排课设置页签**（管理员）：5 个分区，全部是求解模型的可配参数，改完需重新求解才生效。
  - **学期参数**：教学周数、第 1 周周一、教学日（多选）、每日节数、跨校区通行分钟、每课次候选教室上限。
  - **节次作息**：逐节编辑开始/结束时间与时段（上午/下午/晚上）；同段且连续才允许连堂。
  - **教学日历**：特殊教学周（考试周/实践周不排课）+ 特殊日期（节假日/停课/调休上课/补课，调休选周几、补课选补哪天）。
  - **约束权重**：26 项硬约束只读展示；12 项软约束逐项开关 + 权重（0~1000），支持恢复默认。
  - **时间规则**：全局/教师/班级/教室/课程/教学任务 × 禁排/固定/回避/优先，含 6 个常用模板；硬规则立即排除候选，软规则只进目标函数。
- **课表显示设置**（工具条「显示设置」）：舒适/紧凑密度、是否显示班级/教师/教室、周次角标、隐藏无课节次、只看工作日；保存在本机浏览器，只影响呈现。
- **排课任务高级参数**：求解范围（整学期/按学院专业/指定教学任务）、随机种子、基准版本、软约束权重按次覆盖。
- 只读账号（仅 `schedule:read`）看不到新建/编辑/删除、开始排课与调整入口；发布按钮只在草稿版本出现。
- 前端不做组织与账号维护：行政班在「管理 → 组织」、教师账号在「管理 → 用户」创建，这里只挂排课档案，
  避免与既有用户/组织模块重复建实体。
- 只有 `timetable:read` 的教师/学生进入同一页面时，自动切换为「我的课表」：不显示页签与工具区，
  只渲染本人已发布课表（教师按任教课程、学生按所在行政班），不会触发任何需要 `schedule:read` 的请求。

## 9. 测试与验证

详见 `docs/10-scheduling-test.md`。摘要：

- `mvn test -Dtest=SchedulingSolverTest`：12 个算法场景（最小可行、教师/教室/班级冲突、容量、禁排、连堂、固定、锁定、软约束、无解诊断、取消）。
- `mvn verify -Pit -Dit.test=SchedulingIT`：5 个端到端场景（全流程、学时不一致、容量不足、权限与重复启动、锁定重排）。
- `mvn verify -Pit`：全量 142 个测试通过。
- 端到端 HTTP 实测（真实服务 + 真实 MySQL）：创建学期/教室/课程/教学班/任务 → 预检 0 错误 → 求解 `OPTIMAL`、4/4 次课、复核 0 违反、评分 84.38 → 冲突移动 409 → 调整/锁定 → 发布 PUBLISHED → 教师课表可见。

## 10. 部署

详见 `docs/11-scheduling-deployment.md`。要点：JDK 17、MySQL 8（生成列）、OR-Tools 原生库随依赖自带、
`app.scheduling.solver-threads`（默认 2）与 `queue-capacity`（默认 8）、每任务 `timeLimitSeconds`（默认 60）。

## 11. 已知限制与后续

1. 同学期并发求解的互斥基于进程内状态，多实例部署需改为数据库锁或消息队列。
2. 进程异常退出后 RUNNING 任务不会自动收敛，需启动巡检或人工置 FAILED。
3. 未在数百任务规模压测；建议按学院/年级分批（`orgUnitId`/`taskIds`），并下调 `max_room_candidates`。
4. 课时按“每周重复 + 任务周次”建模，同一课次不能在不同周换时间/教室（调休/单双周已支持）。
5. 一“节”按 1 学时计，未建模小学时与实验折算。
6. 单目标加权和，未实现分层/字典序多目标优化。
7. 第一阶段无前端；通过 Swagger UI / Postman 调试。
8. 未接入真实选课名单，教学班人数依赖档案或选修抽取人数；与 `course_selection` 仅通过 `linked_course_id` 预留。

恢复开发时：先读本节与第 0 节进度表，再按第 10 节命令构建验证。

