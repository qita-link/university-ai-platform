# 09 自动排课模块数据库设计

> 迁移脚本：`src/main/resources/db/migration/V8__init_scheduling.sql`（主表结构）
> 和 `V9__scheduling_job_log_and_publish.sql`（任务日志、发布记录、`schedule:adjust` 权限点）。
> 约定与 V1~V7 一致：InnoDB / utf8mb4 / `DATETIME(3)` / BIGINT 自增；状态用 VARCHAR 存枚举名。

---

## 1. 设计原则

1. **不重复建业务实体**：学期复用 `semester`，教师复用 `sys_user`（含 TEACHER 角色），
   行政班复用 `sys_org_unit`（`type=CLASS`），学院/系复用 `sys_org_unit`。
   排课专属属性放在 `sch_*` 扩展档案表（一对一）。
2. **概念分层**：课程库（`sch_course`）≠ 学期内开课（阶段六 `course`，含选课）≠ 教学任务（`sch_task`）
   ≠ 课时安排（`sch_assignment`）。课程、教学班、教学任务、课时安排四张表不合并。
3. **时间统一建模**：`day_of_week` 1~7、`period` 1~16、`week` 1~30 全部为整数，周次集合在应用层用位图表示；
   不存“周一 1-2 节”这类字符串。
4. **版本化**：一次求解产生一个 `sch_version`；调整/复制产生新版本；`sch_assignment` 挂在版本下，
   因此历史课表可完整回溯与对比。发布状态由生成列 + 唯一索引保证“每学期最多一个 PUBLISHED”。
5. **不物理删除关键数据**：课程、任务用 `status` 停用；已被课表引用的任务禁止删除（服务层校验 + 外键 RESTRICT）。
6. **可安全升级**：V8/V9 均为新增表/新增行，可在已有库上直接执行；未修改 V1~V7 的任何对象。

---

## 2. 表清单与关系

```
semester (V5) 1──1 sch_semester_config
   │ 1──n sch_period / sch_calendar_week / sch_calendar_date
   │ 1──n sch_teaching_class 1──n sch_teaching_class_member ──> sys_org_unit(CLASS)
   │ 1──n sch_task 1──n sch_task_teacher ──> sys_user
   │            │ 1──n sch_task_room ──> sch_room
   │            └──> sch_course, sch_teaching_class
   │ 1──n sch_time_rule / sch_constraint_config
   │ 1──n sch_job 1──n sch_job_log
   │ 1──n sch_version 1──n sch_assignment ──> sch_task, sch_room
                 │ 1──n sch_version_issue
                 │ 1──n sch_adjustment_log
                 └──n sch_publish_record
sch_campus 1──n sch_room；sch_campus_travel（校区通行时间）
sch_teacher_profile ──> sys_user；sch_class_profile ──> sys_org_unit(CLASS)
sch_teacher_course（教师可授课程）──> sys_user + sch_course
```

| # | 表 | 用途 | 关键约束 |
|---|---|---|---|
| 1 | `sch_semester_config` | 学期排课配置 | PK=semester_id；周数 1~30、每日节数 1~16 |
| 2 | `sch_period` | 节次作息 | UK(semester_id, period_no)；start<end |
| 3 | `sch_calendar_week` | 特殊教学周 | UK(semester_id, week_no)；`teaching_allowed=false` 不排理论课 |
| 4 | `sch_calendar_date` | 节假日/停课/调休/补课 | UK(semester_id, event_date) |
| 5 | `sch_campus` | 校区 | UK(code) |
| 6 | `sch_campus_travel` | 校区间通行分钟 | PK(from,to)，应用层保证 from<to |
| 7 | `sch_room` | 教室 | UK(code)；容量 1~5000；`version` 乐观锁 |
| 8 | `sch_teacher_profile` | 教师排课档案 | PK=teacher_id；上限字段可空=不限制 |
| 9 | `sch_class_profile` | 行政班档案 | PK=org_unit_id；人数 0~5000 |
| 10 | `sch_course` | 课程库 | UK(code)；学时 1~2000；优先级 1~10 |
| 11 | `sch_teacher_course` | 教师可授课程 | PK(teacher_id, course_id) |
| 12 | `sch_teaching_class` | 教学班 | UK(semester_id, code) |
| 13 | `sch_teaching_class_member` | 教学班成员（行政班） | PK(teaching_class_id, org_unit_id)；选修抽取必须填人数 |
| 14 | `sch_task` | 教学任务 | UK(semester_id, code)；周次/次数/节数区间校验 |
| 15 | `sch_task_teacher` | 任务教师（MAIN/CO） | PK(task_id, teacher_id) |
| 16 | `sch_task_room` | 任务教室（REQUIRED/PREFERRED/FORBIDDEN） | PK(task_id, room_id) |
| 17 | `sch_time_rule` | 时间规则 | 索引(semester_id,target_type,target_id)；节次/周次/权重校验 |
| 18 | `sch_constraint_config` | 软约束权重 | PK(semester_id, code)；权重 0~1000 |
| 19 | `sch_version` | 课表版本 | UK(semester_id, version_no)；UK(semester_id, published_key) |
| 20 | `sch_job` | 排课任务（一次求解） | 索引(semester_id, created_at)、(status) |
| 21 | `sch_assignment` | 课时安排 | UK(version_id, task_id, session_index)；索引(version_id, day, start) |
| 22 | `sch_version_issue` | 版本问题清单 | 索引(version_id, severity) |
| 23 | `sch_adjustment_log` | 人工调整记录 | 索引(version_id, created_at) |
| 24 | `sch_job_log` | 任务日志 | 索引(job_id, id)；FK 级联删除 |
| 25 | `sch_publish_record` | 发布/撤销/归档记录 | 索引(semester_id, created_at)、(version_id) |

---

## 3. 关键表字段说明

### 3.1 `sch_semester_config`

| 字段 | 类型 | 说明 |
|---|---|---|
| semester_id | BIGINT PK | 关联 `semester`，级联删除 |
| teaching_weeks | TINYINT | 教学周数 1~30 |
| first_week_start | DATE | 第 1 周的周一（服务层校验必须是周一） |
| teaching_days | VARCHAR(32) | 教学日，如 `1,2,3,4,5` |
| periods_per_day | TINYINT | 每日节数 |
| default_travel_minutes | INT | 未配置校区通行时间时的默认值（软约束 CAMPUS_TRAVEL 用） |
| max_room_candidates | INT | 每个任务保留的候选教室数上限（控制模型规模） |

### 3.2 `sch_period`

`period_no` 1~16、`start_time`/`end_time`、`section`
（`MORNING`/`AFTERNOON`/`EVENING`）。**同一 section 且编号连续才能连堂**，午休自然隔断。

### 3.3 `sch_calendar_date`

| date_type | 含义 | 附加字段 |
|---|---|---|
| `HOLIDAY` | 法定节假日，当天不上课 | — |
| `SUSPENDED` | 临时停课 | — |
| `WORKDAY` | 调休上课日（通常周末），按某天课表上课 | `follow_day_of_week` 必填 |
| `MAKEUP` | 补课日 | `follow_date` 必填（补哪一天的课） |

应用层据此计算按星期的“损失周次/额外周次”位图，用于软约束 `HOLIDAY_LOSS` 与结果展示。

### 3.4 `sch_task`（教学任务）

| 字段 | 说明 |
|---|---|
| start_week/end_week/week_parity | 上课周次范围与单双周 |
| sessions_per_week | 每周上课次数 |
| periods_per_session | 单次连续节数（连堂） |
| total_hours | 总学时；应等于 单次节数 × 每周次数 × 有效教学周数 |
| room_required | 是否需要教室（实践课可为 false） |
| room_type/required_features | 为空时回落到课程库默认值 |
| priority | 1~10，为空取课程优先级 |
| allow_same_day | 是否允许同一天两次 |
| uniform_start | 每次课是否必须同一起始节 |
| linked_course_id | 可选关联阶段六的学期内开课 `course.id` |
| status | ACTIVE/DISABLED |

### 3.5 `sch_time_rule`（统一时间规则）

- `target_type`：`GLOBAL`/`TEACHER`/`CLASS`/`TEACHING_CLASS`/`ROOM`/`COURSE`/`TASK`；
  `GLOBAL` 时 `target_id` 为 NULL。
- `rule_kind`：`FORBIDDEN`（硬，禁排/不可用/维护）、`FIXED`（硬，固定时段，仅 COURSE/TASK）、
  `AVOID`（软，回避）、`PREFERRED`（软，优先）。
- `day_of_week` 为空表示每个教学日；`start_week/end_week` 为空表示整个学期（求解时与可教学周求交）。
- `weight` 仅对软规则有意义（1~10，作为该规则的倍数）。

### 3.6 `sch_version`

| 字段 | 说明 |
|---|---|
| version_no | 学期内递增，UK(semester_id, version_no) |
| source | `SOLVER`（求解产出）/`COPY`（人工复制） |
| job_id / base_version_id | 来源任务与基准版本 |
| status | `DRAFT`/`PUBLISHED`/`ARCHIVED` |
| published_key | **生成列** `IF(status='PUBLISHED',1,NULL)`，配合唯一索引保证每学期最多一个已发布版本 |
| total_sessions / placed_sessions | 应排/已排课次 |
| hard_violations | 最近一次复核的硬约束违反数 |
| score / soft_penalty / evaluation_json | 评分、软约束扣分、完整评分明细（JSON 快照） |
| version | 乐观锁（任何调整/状态变更递增，由 `@Version` 维护） |

### 3.7 `sch_assignment`（课时安排）

- 一行 = 某教学任务的第 `session_index` 次课（每周重复），周次取任务定义。
- `day_of_week/start_period/end_period` 全为 NULL 表示**未安排**（保留行用于追踪原因与人工放置）。
- `locked`：锁定后重新求解会保留该位置；`origin`：`SOLVER`/`MANUAL`/`LOCKED`/`COPY`。
- UK(version_id, task_id, session_index) 保证同一课次不重复。

### 3.8 `sch_job`（排课任务）

| 字段 | 说明 |
|---|---|
| status | 业务状态 DRAFT/QUEUED/RUNNING/SUCCEEDED/PARTIAL/INFEASIBLE/UNKNOWN/FAILED/CANCELLED |
| solver_status | CP-SAT 原始状态 OPTIMAL/FEASIBLE/INFEASIBLE/UNKNOWN/MODEL_INVALID |
| params_json | 任务参数快照（求解范围、时限、权重覆盖、局部重排任务） |
| stats_json | 候选数/变量数/约束数/构建耗时/已排课次 |
| log_text | CP-SAT 搜索日志（截断 60KB） |
| progress/phase | 进度 0~100 与阶段 LOADING/SOLVING/SAVING |
| result_version_id | 成功后指向新版本 |

---

## 4. 事务与并发

| 场景 | 机制 |
|---|---|
| 同一任务重复启动 | 状态机校验 + 进程内 `runningJobs` 集合；已完成任务只能“重试”（新建任务） |
| 同学期并发求解 | 进程内 `runningSemesters` 互斥，第二个请求返回 409 |
| 求解结果落库 | 单事务写入 `sch_version` + N 条 `sch_assignment` + 问题清单，失败整体回滚 |
| 发布切换 | 单事务内先归档旧 PUBLISHED 再置新版本，唯一索引兜底 |
| 并发调整 | `sch_version.version` 乐观锁 + 调整前后复核 |
| 教室/课程/任务并发修改 | `@Version` 乐观锁（提交 `version` 时校验，不匹配返回 409） |

> 注意：进程内互斥只对单实例有效。多实例部署时需改为数据库行锁
> （`SELECT ... FOR UPDATE` 锁定 `sch_job` 或 `sch_semester_config`）或引入队列，见 11 节“限制”。

---

## 5. 索引与查询

| 查询 | 索引 |
|---|---|
| 按学期取任务 | `idx_sch_task_semester(semester_id, status)` |
| 按教师取任务 | `idx_sch_tt_teacher(teacher_id)` |
| 按版本取课表 | `idx_sch_assignment_slot(version_id, day_of_week, start_period)` |
| 按教室查冲突 | `idx_sch_assignment_room(version_id, room_id)` |
| 按对象取时间规则 | `idx_sch_rule_target(semester_id, target_type, target_id)` |
| 任务状态轮询 | `idx_sch_job_status(status)`、`idx_sch_job_semester(semester_id, created_at)` |
| 版本列表 | `uk_sch_version_no(semester_id, version_no)`、`idx_sch_version_status` |

`sch_assignment` 的 `day_of_week/start_period` 允许 NULL，MySQL 唯一索引对 NULL 不去重，
因此“未安排”的多行可共存（每个 `session_index` 仍唯一）。

---

## 6. 迁移与回滚

```bash
# 应用（Flyway 自动执行，按版本号顺序）
mvn flyway:info      # 或直接启动应用，启动时自动 migrate
mvn flyway:migrate

# 仅校验脚本（不改库）
mvn flyway:validate
```

- V8/V9 只新增对象，**旧库可直接升级**，不需要删库重建。
- 生产环境禁止 `flyway clean`（`spring.flyway.clean-disabled=true`）。
- 如需回滚到 V7：先备份，再手工 `DROP TABLE` 25 张 `sch_*` 表并删除 V8/V9 记录；
  由于排课数据与既有模块无写入依赖，回滚不影响课程/选课/论坛等数据。
- 已在 MySQL 8.0.46（本地）与 8.4（开发库）验证 V1→V9 连续执行通过，并在已有 V1~V7 数据库上验证增量升级。
