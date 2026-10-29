# 08 自动排课模块 REST API 文档

> 适用版本：`0.1.0-SNAPSHOT`（阶段七，自动排课第一阶段）
> 代码位置：`com.campus.platform.scheduling.controller`
> 机器可读规范：`docs/openapi-scheduling.json`（由运行中的服务 `/v3/api-docs` 导出，与实际实现一致）

---

## 1. 访问方式

| 项 | 值 |
|---|---|
| 基础路径 | `http://{host}:{port}/api/v1/scheduling` |
| 认证 | `Authorization: Bearer <JWT>`（登录接口签发，见 `/api/v1/auth/login`） |
| 内容类型 | `application/json; charset=UTF-8`（导入导出为 `text/csv`） |
| 交互文档 | Swagger UI：`http://{host}:{port}/swagger-ui/index.html`；OpenAPI JSON：`/v3/api-docs` |
| 时间格式 | 日期 `yyyy-MM-dd`，时间 `HH:mm:ss`，日期时间 ISO-8601（`2026-10-29T10:14:12.052`） |

排课接口全部要求登录；除 `timetable:read` 外均需要排课相关权限点（见第 4 节）。
Swagger UI 右上角 **Authorize** 填入令牌后即可调试全部接口。

---

## 2. 统一响应格式

成功与失败使用同一结构（`ApiResponse`）：

```json
{
  "success": true,
  "code": "OK",
  "message": "success",
  "data": { },
  "errors": null,
  "traceId": "d02ebb4104914ac084f25d4c364b6bf6",
  "timestamp": "2026-10-29T10:14:08.979232+08:00"
}
```

失败示例（参数校验）：

```json
{
  "success": false,
  "code": "VALIDATION_FAILED",
  "message": "参数校验失败",
  "data": null,
  "errors": [{ "field": "teachingWeeks", "message": "最大不能超过 30" }],
  "traceId": "f28f20cbce8b4224a0e3a514dce8f38b",
  "timestamp": "2026-10-29T10:12:25.675049+08:00"
}
```

### 2.1 HTTP 状态与业务码

| HTTP | code | 场景 |
|---|---|---|
| 200 | `OK` | 成功 |
| 400 | `BAD_REQUEST` / `VALIDATION_FAILED` | 参数格式错误、字段校验失败、业务入参不合法（如“第 1 周必须是周一”） |
| 401 | `UNAUTHORIZED` | 未登录、令牌过期、令牌版本失效 |
| 403 | `FORBIDDEN` | 缺少权限点（如学生创建教学任务、教师发布课表） |
| 404 | `NOT_FOUND` | 学期/教室/任务/版本/课时不存在 |
| 409 | `CONFLICT` | 唯一约束冲突、乐观锁冲突、课时冲突、任务重复启动、已发布版本不可改 |
| 422 | `BUSINESS_RULE_VIOLATION` | 业务规则不满足（学时不一致、发布前复核未通过、账号未关联班级等） |
| 503 | `SERVICE_UNAVAILABLE` | 求解队列已满 |

错误响应中的 `traceId` 与服务端日志一致，排查时按它检索。

---

## 3. 分页

列表接口使用统一分页参数（`page` 从 1 开始，`size` 1~100，默认 20）：

```json
{
  "items": [ ... ],
  "total": 42,
  "page": 1,
  "size": 20,
  "pages": 3
}
```

分页接口：`GET /teaching-tasks`、`GET /jobs`、`GET /classrooms`。
`GET /teachers`、`GET /classes`、`GET /courses`、`GET /versions` 返回数组（数据量可控）。

---

## 4. 权限点

| 权限点 | 含义 | 默认授予 |
|---|---|---|
| `schedule:read` | 查看排课基础数据、任务、版本与全部课表 | ADMIN、ACADEMIC_ADMIN |
| `schedule:manage` | 维护基础数据、创建/启动排课任务、局部重排、复制版本 | ADMIN、ACADEMIC_ADMIN |
| `schedule:adjust` | 调整课表：移动课时、锁定/解锁 | ADMIN、ACADEMIC_ADMIN |
| `schedule:publish` | 发布 / 撤销发布课表 | ADMIN、ACADEMIC_ADMIN |
| `schedule:preference:self` | 教师维护本人的时间偏好 | TEACHER |
| `timetable:read` | 查看本人已发布课表 | TEACHER、STUDENT（及管理员） |

ACADEMIC_ADMIN（教务员）角色由 `V8__init_scheduling.sql` 创建。所有写接口在 Controller 上做 `@PreAuthorize`，
并在 Service 内再次调用 `Permissions.require`（防止 AI 工具等非 HTTP 入口绕过）。

---

## 5. 状态说明

### 5.1 排课任务（`sch_job.status`）

| 状态 | 含义 |
|---|---|
| `DRAFT` | 已创建，未启动 |
| `QUEUED` | 已提交，等待线程池 |
| `RUNNING` | 正在加载数据或求解 |
| `SUCCEEDED` | 所有课次都排上了 |
| `PARTIAL` | 求得解，但有课次未安排（原因见版本问题清单与诊断接口） |
| `INFEASIBLE` | 求解器**证明**当前模型无可行解 |
| `UNKNOWN` | 达到时间上限仍未证明可行性（可能没有解，也可能只是没算完） |
| `FAILED` | 预检未通过、求解异常、或结果未通过独立复核 |
| `CANCELLED` | 被取消 |

`solverStatus` 保留 CP-SAT 原始状态：`OPTIMAL` / `FEASIBLE` / `INFEASIBLE` / `UNKNOWN` / `MODEL_INVALID`。
业务状态与原始状态不是一一对应：`FEASIBLE` 可能映射为 `SUCCEEDED` 或 `PARTIAL`，`FAILED` 也可能来自预检或复核。

### 5.2 课表版本（`sch_version.status`）

`DRAFT` 可调整 → `PUBLISHED` 已发布（每学期最多一个）→ `ARCHIVED` 归档（发布新版本时旧版本自动归档，撤销发布也进入归档）。

---

## 6. 接口清单

### 6.1 学期与教学日历

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/terms` | read | 学期列表（含排课配置摘要） |
| POST | `/terms` | manage | 新建学期 + 排课配置 + 默认作息 |
| GET | `/terms/{semesterId}` | read | 学期详情 |
| PUT | `/terms/{semesterId}/config` | manage | 修改教学周数、第 1 周周一、教学日、每日节数 |
| GET | `/terms/{semesterId}/periods` | read | 节次作息 |
| PUT | `/terms/{semesterId}/periods` | manage | 整体替换节次作息 |
| GET | `/terms/{semesterId}/calendar` | read | 特殊教学周 + 特殊日期 |
| PUT | `/terms/{semesterId}/calendar` | manage | 整体替换教学日历 |
| GET | `/terms/{semesterId}/constraints` | read | 约束清单与权重 |
| PUT | `/terms/{semesterId}/constraints` | manage | 修改软约束权重 |
| POST | `/terms/{semesterId}/precheck` | read | 排课数据预检 |

### 6.2 校区与教室

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET/POST | `/campuses` | read / manage | 校区列表 / 新建 |
| PUT | `/campuses/{campusId}` | manage | 修改校区 |
| GET/PUT | `/campuses/travel` | read / manage | 校区通行时间 |
| GET/POST | `/classrooms` | read / manage | 教室分页 / 新建 |
| GET/PUT | `/classrooms/{classroomId}` | read / manage | 教室详情 / 修改（可带 version 乐观锁） |
| GET/PUT | `/classrooms/{classroomId}/availability?semesterId=` | read / manage | 教室可用时间（维护时段） |

### 6.3 教师与行政班

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/teachers?keyword=` | read | 教师 + 排课档案 + 可授课程 |
| PUT | `/teachers/{teacherId}` | manage | 修改档案（工作量上限、可授课程） |
| GET/PUT | `/teachers/{teacherId}/availability?semesterId=` | read / manage | 教师不可用/偏好时间 |
| PUT | `/teachers/me/preferences?semesterId=` | preference:self | 教师本人维护偏好（仅 AVOID/PREFERRED） |
| GET | `/teachers/{teacherId}/workload` | read | 按已发布版本统计工作量 |
| GET | `/classes?keyword=` | read | 行政班 + 排课档案 |
| PUT | `/classes/{classId}` | manage | 修改班级人数、日上限 |
| GET/PUT | `/classes/{classId}/availability?semesterId=` | read / manage | 班级禁排/偏好 |

### 6.4 课程库、教学班、教学任务

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET/POST | `/courses` | read / manage | 课程库列表 / 新建 |
| GET/PUT/DELETE | `/courses/{courseId}` | read / manage | 详情 / 修改 / 停用 |
| GET/POST | `/teaching-classes?semesterId=` | read / manage | 教学班列表 / 新建（含成员） |
| GET/PUT | `/teaching-classes/{id}` | read / manage | 详情 / 修改 |
| GET | `/teaching-tasks` | read | 教学任务分页（学期/学院/课程/教学班/教师/状态） |
| GET/POST | `/teaching-tasks` | read / manage | 见上 / 新建 |
| GET/PUT/DELETE | `/teaching-tasks/{id}` | read / manage | 详情 / 修改 / 删除（已被课表引用时拒绝） |
| POST | `/teaching-tasks/import?semesterId=` | manage | CSV 批量导入（`text/csv` 或 `text/plain` 请求体；兼容 Excel 的 UTF-8 BOM 与引号包裹字段） |
| GET | `/teaching-tasks/export?semesterId=` | read | 导出 CSV（UTF-8 BOM） |
| GET | `/teaching-tasks/precheck?semesterId=&orgUnitId=` | read | 预检（同 `POST /terms/{id}/precheck`） |

### 6.5 时间规则

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/rules?semesterId=&targetType=&targetId=` | read | 规则列表 |
| GET | `/rules/templates` | read | 规则模板（午休禁排、减少早八等） |
| POST | `/rules` | manage | 新建规则 |
| PUT/DELETE | `/rules/{id}` | manage | 修改 / 删除 |

### 6.6 排课任务（一次求解）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/jobs` | manage | 创建任务 |
| GET | `/jobs?semesterId=&status=&page=&size=` | read | 任务分页 |
| GET | `/jobs/{jobId}` | read | 任务详情 |
| POST | `/jobs/{jobId}/run` | manage | 启动（异步，立即返回） |
| POST | `/jobs/{jobId}/cancel` | manage | 取消（排队中/执行中） |
| POST | `/jobs/{jobId}/retry` | manage | 以相同参数创建新任务 |
| GET | `/jobs/{jobId}/status` | read | 状态与进度 |
| GET | `/jobs/{jobId}/logs` | read | 任务日志 |
| GET | `/jobs/{jobId}/diagnostics` | read | 诊断（预检问题、未安排原因、资源摘要） |

### 6.7 课表版本、调整与发布

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/versions?semesterId=` | read | 版本列表 |
| GET | `/versions/{versionId}` | read | 版本详情 |
| POST | `/versions/{versionId}/copy` | manage | 复制为新草稿 |
| GET | `/versions/{versionId}/compare?otherVersionId=` | read | 版本对比 |
| GET | `/versions/{versionId}/entries` | read | 课时列表（可按教师/班级/教室/课程/星期/节次/周次过滤） |
| GET | `/versions/{versionId}/timetable` | read | 课表网格（教师/班级/教室维度） |
| GET | `/versions/{versionId}/unplaced` | read | 未安排课程及原因 |
| GET | `/versions/{versionId}/evaluation` | read | 评分明细 + 冲突（实时复核） |
| GET | `/versions/{versionId}/conflicts` | read | 冲突列表 |
| POST | `/versions/{versionId}/check` | **adjust** | 复核并固化（会写库：重写问题清单与统计） |
| GET | `/versions/{versionId}/entries/{entryId}/candidates` | read | 某节课的可选位置（最多 300 条） |
| PUT | `/versions/{versionId}/entries/{entryId}` | adjust | 移动/取消安排 |
| PUT | `/versions/{versionId}/entries/{entryId}/lock` | adjust | 锁定/解锁 |
| GET | `/versions/{versionId}/adjustments` | read | 调整历史 |
| POST | `/versions/{versionId}/reschedule?taskIds=&timeLimitSeconds=&reason=` | manage | 局部重排（生成新版本） |
| POST | `/versions/{versionId}/publish` | publish | 发布（强制复核） |
| POST | `/versions/{versionId}/unpublish` | publish | 撤销发布 |
| GET | `/publish-records?semesterId=` | read | 发布记录 |
| GET | `/versions/{versionId}/export.csv` | read | 导出课表 CSV |
| GET | `/timetables/me?semesterId=` | timetable:read | 本人已发布课表 |
| GET | `/timetables/periods?semesterId=` | timetable:read | 节次作息（课表左侧时间列）；学期未配作息时沿用「进行中 → 开始日期最晚」的已配置学期 |
| GET | `/timetables/room?roomId=&semesterId=` | schedule:read 或 schedule:preference:self | 教室课表（按教室查已发布课表；仅教师与管理员，学生 403） |
| GET | `/scheduling/terms/{semesterId}/teacher-availability/{teacherId}` | schedule:read | 教师可来时间窗（星期 + 半天，空表示不限制） |
| PUT | `/scheduling/terms/{semesterId}/teacher-availability/{teacherId}` | **schedule:manage** | 设置教师可来时间窗（仅管理员）：落成 AVAILABLE 时间规则，求解与复核按硬约束处理 |

---

## 7. 典型调用流程（curl）

### 7.1 登录获取令牌

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"******"}' | jq -r '.data.accessToken')
```

### 7.2 建立学期与作息

```bash
curl -X POST http://localhost:8080/api/v1/scheduling/terms \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{
        "code": "2026-2027-1",
        "name": "2026-2027 学年第一学期",
        "startDate": "2026-09-07",
        "endDate": "2027-01-03",
        "teachingWeeks": 16,
        "firstWeekStart": "2026-09-07",
        "teachingDays": [1,2,3,4,5],
        "periodsPerDay": 8
      }'
```

响应（截断）：

```json
{
  "success": true, "code": "OK",
  "data": {
    "id": 5, "code": "2026-2027-1", "name": "2026-2027 学年第一学期",
    "startDate": "2026-09-07", "endDate": "2027-01-03", "status": "DRAFT",
    "config": {
      "teachingWeeks": 16, "firstWeekStart": "2026-09-07",
      "teachingDays": [1,2,3,4,5], "periodsPerDay": 8,
      "defaultTravelMinutes": 30, "maxRoomCandidates": 12,
      "periodCount": 8, "configured": true
    }
  }
}
```

> 未配置节次时会自动生成 8 节默认作息（08:00 起，上午 1–4、下午 5–8）；可用 `PUT /terms/{id}/periods` 覆盖。

### 7.3 教室与通行时间

```bash
curl -X POST http://localhost:8080/api/v1/scheduling/classrooms \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"code":"A101","name":"A101 多媒体教室","campusId":1,"building":"一教","capacity":60,
       "roomType":"MULTIMEDIA","features":["PROJECTOR"]}'
```

`roomType`：`NORMAL` / `MULTIMEDIA` / `COMPUTER_LAB` / `LANGUAGE_LAB` / `LAB` / `GYM` / `OTHER`。
教室维护时段：

```bash
curl -X PUT "http://localhost:8080/api/v1/scheduling/classrooms/1/availability?semesterId=5" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '[{"semesterId":5,"targetType":"ROOM","targetId":1,"kind":"FORBIDDEN",
        "dayOfWeek":3,"startPeriod":1,"endPeriod":8,"note":"周三上午设备检修"}]'
```

### 7.4 教师与班级档案

```bash
curl -X PUT http://localhost:8080/api/v1/scheduling/teachers/3 \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"maxWeeklyPeriods":20,"maxDailyPeriods":6,"maxDailySessions":3,"maxConsecutivePeriods":4,
       "courseIds":[1,2]}'

curl -X PUT http://localhost:8080/api/v1/scheduling/classes/4 \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"studentCount":40,"grade":"2026","maxDailyPeriods":8,"maxConsecutivePeriods":4}'
```

### 7.5 课程库、教学班、教学任务

```bash
curl -X POST http://localhost:8080/api/v1/scheduling/courses \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"code":"CS101","name":"Java 程序设计","courseType":"THEORY","credit":4.0,
       "totalHours":64,"priority":8}'

curl -X POST http://localhost:8080/api/v1/scheduling/teaching-classes \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"semesterId":5,"code":"CS101-01","name":"Java 教学班 1",
       "members":[{"classId":4,"fullClass":true}]}'

curl -X POST http://localhost:8080/api/v1/scheduling/teaching-tasks \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"semesterId":5,"code":"T-CS101-01","courseId":1,"teachingClassId":1,
       "startWeek":1,"endWeek":16,"weekParity":"ALL",
       "sessionsPerWeek":2,"periodsPerSession":2,"totalHours":64,
       "teacherIds":[3],"allowSameDay":false,"uniformStart":false}'
```

学时口径：`totalHours = periodsPerSession × sessionsPerWeek × 有效教学周数`。
上例为 2 × 2 × 16 = 64。不一致时**允许保存**，但预检返回 `HOURS_CONSISTENCY` 错误并阻止求解。

### 7.6 预检

```bash
curl -X POST "http://localhost:8080/api/v1/scheduling/terms/5/precheck" \
  -H "Authorization: Bearer $TOKEN"
```

```json
{
  "success": true,
  "data": {
    "errorCount": 0,
    "warningCount": 0,
    "solvable": true,
    "summary": "共 2 个教学任务，每周 4 次课；启用教室 4 间，最大容量 80，最大教学班人数 40",
    "issues": []
  }
}
```

有错误时 `issues` 形如：

```json
{
  "severity": "ERROR",
  "code": "HOURS_CONSISTENCY",
  "taskId": 12,
  "message": "教学任务 T-CS101-01 Java 教学任务 学时不一致：2 节/次 × 2 次/周 × 有效教学周 16 周 = 64 学时，任务要求 30 学时（有效周次：1-16）"
}
```

### 7.7 创建并启动排课任务

```bash
curl -X POST http://localhost:8080/api/v1/scheduling/jobs \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"semesterId":5,"name":"2026 秋全量排课","mode":"FULL",
       "timeLimitSeconds":60,"workers":4,"randomSeed":1,
       "allowPartial":true,"keepLocked":true,
       "weightOverrides":{"TEACHER_PREFERENCE":12}}'

curl -X POST http://localhost:8080/api/v1/scheduling/jobs/1/run \
  -H "Authorization: Bearer $TOKEN"
```

启动接口立即返回：

```json
{"success":true,"data":{"id":1,"status":"QUEUED","phase":"QUEUED","progress":0,"elapsedMillis":0}}
```

轮询状态：

```bash
curl "http://localhost:8080/api/v1/scheduling/jobs/1/status" -H "Authorization: Bearer $TOKEN"
```

```json
{
  "success": true,
  "data": {
    "id": 1, "status": "SUCCEEDED", "phase": "SUCCEEDED", "progress": 100,
    "elapsedMillis": 1916, "solverStatus": "OPTIMAL",
    "objective": 20, "bestBound": 20,
    "message": "排课完成，全部课次已安排"
  }
}
```

日志：

```json
[
  {"level":"INFO","message":"任务已创建：2026 秋全量排课（FULL）","createdAt":"2026-10-29T10:14:09.769"},
  {"level":"INFO","message":"开始加载排课数据","createdAt":"2026-10-29T10:14:09.802"},
  {"level":"INFO","message":"已加载 2 个教学任务、1 个行政班、4 间教室","createdAt":"2026-10-29T10:14:10.003"},
  {"level":"INFO","message":"求解结束：状态 OPTIMAL，目标值 20，耗时 0.18 秒","createdAt":"2026-10-29T10:14:10.204"},
  {"level":"INFO","message":"已生成版本 #1，已排 4/4 次课，评分 84.38","createdAt":"2026-10-29T10:14:10.401"},
  {"level":"INFO","message":"任务结束：SUCCEEDED","createdAt":"2026-10-29T10:14:10.512"}
]
```

### 7.8 查看课表与评分

```bash
curl "http://localhost:8080/api/v1/scheduling/versions/1/entries?teacherId=3" -H "Authorization: Bearer $TOKEN"
curl "http://localhost:8080/api/v1/scheduling/versions/1/timetable?classId=4" -H "Authorization: Bearer $TOKEN"
curl "http://localhost:8080/api/v1/scheduling/versions/1/evaluation" -H "Authorization: Bearer $TOKEN"
```

评分响应：

```json
{
  "score": 84.38, "placedSessions": 4, "totalSessions": 4,
  "hardViolations": 0, "softPenalty": 18,
  "details": [
    {"code":"TEACHER_PREFERENCE","label":"教师时间偏好","weight":10,"applicable":4,"violated":0,"penalty":0,"satisfaction":1.0},
    {"code":"CLASS_DAILY_BALANCE","label":"班级每日课量均衡","weight":3,"applicable":5,"violated":1,"penalty":3,"satisfaction":0.8}
  ],
  "conflicts": []
}
```

### 7.9 调整与锁定

冲突时：

```bash
curl -X PUT http://localhost:8080/api/v1/scheduling/versions/1/entries/2 \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"dayOfWeek":1,"startPeriod":2,"reason":"冲突演示"}'
```

```json
{"success":false,"code":"CONFLICT","message":"目标位置与已有课程冲突：T2 周一 第2节"}
```

合法移动 / 取消安排 / 锁定：

```bash
curl -X PUT .../versions/1/entries/2 -d '{"dayOfWeek":5,"startPeriod":7,"reason":"调至周五"}'
curl -X PUT .../versions/1/entries/2 -d '{"dayOfWeek":null,"reason":"临时停开"}'
curl -X PUT .../versions/1/entries/2/lock -d '{"locked":true,"reason":"教务主任指定"}'
```

`dayOfWeek=null` 表示取消该课次安排（保留行，便于追踪原因）。

### 7.10 复核、发布、导出

```bash
curl -X POST .../versions/1/check      # 复核并固化
curl -X POST .../versions/1/publish -d '{"reason":"教务处审核通过"}'
curl -X POST .../versions/1/unpublish -d '{"reason":"发现冲突，撤回"}'
curl -OJ .../versions/1/export.csv     # 下载 CSV
```

发布前若存在硬约束违反：

```json
{
  "success": false,
  "code": "BUSINESS_RULE_VIOLATION",
  "message": "发布前复核未通过：2 条硬约束违反，不能发布。教师冲突：T1 ... ；教室冲突：..."
}
```

### 7.11 教师/学生查看本人课表

```bash
curl "http://localhost:8080/api/v1/scheduling/timetables/me?semesterId=5" -H "Authorization: Bearer $TEACHER_TOKEN"
```

返回 `scope=TEACHER`（教师）或 `scope=CLASS`（学生所在行政班），只含**已发布**版本。
不传 `semesterId` 时按「当前学期已发布 → 最近一次发布」兜底，所以学期还是草稿状态时也能看到最近一份课表；
若该学期从未发布过任何版本，返回 404。

---

## 8. 关键请求字段说明

### 8.1 教学任务 `TeachingTaskRequest`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| semesterId | long | 是 | 学期 |
| code | string(48) | 是 | 学期内唯一 |
| courseId | long | 是 | 课程库课程 |
| teachingClassId | long | 是 | 教学班（须属于同一学期） |
| orgUnitId | long | 否 | 开课单位，用于按学院筛选排课范围 |
| startWeek / endWeek | int | 是 | 1~30，结束不早于开始 |
| weekParity | string | 否 | `ALL`/`ODD`/`EVEN` |
| sessionsPerWeek | int | 是 | 1~14 |
| periodsPerSession | int | 是 | 1~8（连堂节数） |
| totalHours | int | 否 | 为空按 周次数×单次节数 估算；预检校验一致性 |
| roomRequired | bool | 否 | 默认 true |
| roomType / requiredFeatures | string/list | 否 | 为空取课程默认值 |
| priority | int | 否 | 1~10，影响软约束“高优先级排上午” |
| allowSameDay | bool | 否 | 是否允许同一天两次 |
| uniformStart | bool | 否 | 每次课必须同一起始节 |
| teacherIds | long[] | 是 | 至少一名；第一位为 MAIN |
| rooms | array | 否 | `{roomId, mode}`，mode=`REQUIRED`/`PREFERRED`/`FORBIDDEN` |
| version | int | 否 | 修改时乐观锁 |

### 8.2 排课任务 `JobCreateRequest`

| 字段 | 类型 | 说明 |
|---|---|---|
| semesterId | long | 必填 |
| name | string | 必填 |
| mode | string | `FULL`（默认）或 `INCREMENTAL` |
| baseVersionId | long | 基准版本；INCREMENTAL 必填 |
| taskIds | long[] | 参与任务；空=学期内全部 ACTIVE |
| orgUnitId | long | 按开课单位过滤 |
| timeLimitSeconds | int | 1~3600，默认 60 |
| workers | int | 1~32，默认 4；`workers=1` + 固定 seed 时结果可复现 |
| randomSeed | int | 默认 1 |
| allowPartial | bool | 默认 true；false 时要求全部排满，否则 INFEASIBLE |
| keepLocked | bool | 默认 true；FULL 模式下保留锁定课次 |
| weightOverrides | map | 覆盖软约束权重，如 `{"SESSION_SPREAD":8}` |
| replanTaskIds | long[] | 局部重排：只放开这些任务，其余固定（INCREMENTAL） |

### 8.3 调整 `AdjustRequest`

| 字段 | 类型 | 说明 |
|---|---|---|
| dayOfWeek | int | 1~7；为 null 表示取消安排 |
| startPeriod | int | 1~16；连续 `periodsPerSession` 节自动推算结束节 |
| roomId | long | 需要教室的任务必须给（或沿用原教室） |
| reason | string | 记录到调整历史 |
| force | bool | true 时允许移动已锁定课时（默认拒绝） |

### 8.4 时间规则 `TimeRuleRequest`

| 字段 | 说明 |
|---|---|
| targetType | `GLOBAL`/`TEACHER`/`CLASS`/`TEACHING_CLASS`/`ROOM`/`COURSE`/`TASK` |
| targetId | 除 GLOBAL 外必填 |
| kind | `FORBIDDEN`（硬）/`FIXED`（硬，仅 COURSE/TASK）/`AVOID`（软）/`PREFERRED`（软） |
| dayOfWeek | 为空表示每个教学日 |
| startPeriod / endPeriod | 节次区间 |
| startWeek / endWeek | 周次区间（默认 1~30，求解时与学期可教学周求交） |
| weight | 软规则倍数 1~10 |

---

## 9. 错误与边界行为

| 场景 | 返回 |
|---|---|
| 第 1 周开始日期不是周一 | 400 `BAD_REQUEST`：`第 1 周的开始日期必须是周一（当前为 TUESDAY）` |
| 教学班属于其它学期 | 400：`教学班属于其它学期` |
| 同一学期任务编号重复 | 409 `CONFLICT` |
| 修改时 version 不匹配 | 409：`教学任务已被他人修改，请刷新后重试` |
| 学期已有发布中的课表时改配置/作息/日历 | 409：`该学期已有发布中的课表，不能修改排课配置；请先撤销发布` |
| 创建排课任务时包含已停用教学任务 | 400：`教学任务已停用，不能参与排课：[...]` |
| 只有 `schedule:read` 的账号调用复核 | 403（复核会写库，需要 `schedule:adjust`） |
| 重复启动同一任务 | 409：`任务正在执行，请勿重复启动` |
| 同学期已有任务在执行 | 409：`该学期已有排课任务（ID x）在执行...` |
| 已完成任务再次启动 | 409：`任务已完成，如需重新计算请使用重试（会创建新任务）` |
| 非草稿版本调整 | 409：`只有草稿版本可以调整；已发布版本请先复制出新版本` |
| 移动已锁定课时 | 409：`该课时已锁定，如需调整请先解锁（或使用 force=true 强制调整）` |
| 移动到禁排/固定冲突时段 | 409：`目标时段被禁排规则覆盖（教师不可用时间）` |
| 移动到已有课的位置 | 409：`目标位置与已有课程冲突：T2 周一 第2节` |
| 同任务同日两次（未开 allowSameDay） | 409：`该教学任务不允许同一天安排两次课` |
| 学时不一致 | 预检 ERROR；启动任务后 `FAILED` + `PRECHECK_FAILED` |
| 教室容量/类型不满足 | 预检 ERROR（`NO_CANDIDATE`，附过滤统计） |
| 学生查看无关联班级的课表 | 422：`当前账号没有关联教师或行政班，无法查看个人课表` |

---

## 10. 调试建议

1. **Swagger UI**：`/swagger-ui/index.html` → Authorize → 逐个调用；响应示例与本文一致。
2. **Postman**：导入 `docs/openapi-scheduling.json`（OpenAPI 3.0.1），环境变量 `baseUrl`、`token`。
3. **预检先行**：任何 `FAILED` 任务先看 `GET /jobs/{id}/diagnostics`，再决定是补数据还是放宽约束。
4. **复现求解**：固定 `randomSeed` 且 `workers=1`，同一输入可得到同一课表，便于对比。
5. **发布后再改时间语义会被拒绝**：教学周数/作息/日历影响已发布课表的真实日期，必须先 `unpublish`。
5. **对比调整效果**：`GET /versions/{a}/compare?otherVersionId={b}` 返回新增/移除/改时间/换教室的逐条差异。
