# 成绩绩点、评教、投诉建议、空教室与班级考勤

2026-10-30 · 对应迁移 `V10__init_grade_feedback_attendance.sql`

这篇文档记录第八阶段（校务服务）的设计与实现：学生成绩与绩点、学生评教、投诉建议、
空教室查询，以及请假双审批、销假和按班按节的考勤。办事与问答已彻底合并为一个页面：
同一个输入框先查知识库，无依据时自动交给工具，写操作仍在消息流里出确认单；不再有独立首页与办事页。
另补充了两个只读 AI 工具，使问答可以直接查空教室和成绩。

---

## 1. 模块与代码位置

| 模块 | 包 | 说明 |
|---|---|---|
| 成绩与绩点 | `com.campus.platform.grade` | 教师录入/发布，学生看成绩单与 GPA |
| 学生评教 | `com.campus.platform.evaluation` | 学生打分，教师看聚合与匿名留言 |
| 投诉建议 | `com.campus.platform.feedback` | 学生提交（可匿名），校方受理回复 |
| 空教室 | `com.campus.platform.classroom` | 当天/一周空闲教室，占用取两套课表的并集 |
| 班级考勤 | `com.campus.platform.attendance` | 按班、课程、日期、节次建场次并逐人登记 |
| 平台设置 | `com.campus.platform.setting` | 请假审批流程等运行期开关 |
| 请假（改造） | `com.campus.platform.course` | 双级审批、销假、全校查询、导员配置 |

---

## 2. 数据表（V10 新增/变更）

| 表 | 用途 | 关键约束 |
|---|---|---|
| `grade_rule` | 分数区间 → 绩点/等级 | `(min_score, max_score)` 唯一；内置 4.0 制十档 |
| `course_grade` | 一门课一名学生一条成绩 | `(course_id, student_id)` 唯一；`DRAFT → PUBLISHED` |
| `teaching_evaluation` | 学生评教 | `(course_id, teacher_id, student_id)` 唯一，防重复评教 |
| `campus_feedback` | 投诉与建议 | 保留 `student_id` 追溯，匿名在接口层隐藏 |
| `class_counselor` | 班级导员（可多名） | 复合主键 `(class_id, counselor_id)` |
| `leave_approval` | 每级审批流水 | `(leave_id, stage)` 唯一，防同级重复结论 |
| `attendance_session` | 考勤场次 | `(course_id, class_id, session_date, start_period)` 唯一 |
| `attendance_record` | 每人每场状态 | `(session_id, student_id)` 唯一 |
| `platform_setting` | 键值设置 | 主键 `setting_key` |
| `leave_request`（变更） | 增加 `class_id/counselor_id/approval_order/total_stages/current_stage/revoked_*` | 旧数据默认 `TEACHER` 单级，兼容 |

`course_grade` 保存绩点与等级快照：之后调整 `grade_rule` 不会改写历史成绩的绩点。

---

## 3. 权限点

V10 新增 11 个权限点，并按角色授权：

| 权限点 | 说明 | ADMIN | TEACHER | STUDENT | STUDENT_UNION |
|---|---|:-:|:-:|:-:|:-:|
| `grade:read` | 看本人成绩 | ✓ | ✓ | ✓ | ✓ |
| `grade:manage` | 录本人任教课程成绩 | ✓ | ✓ | | |
| `grade:manage_all` | 管理全部成绩/规则 | ✓ | | | |
| `evaluation:submit` | 提交评教 | ✓ | | ✓ | ✓ |
| `evaluation:read` | 看本人课程评教 | ✓ | ✓ | | |
| `feedback:submit` | 提交投诉建议（全员） | ✓ | ✓ | ✓ | ✓ |
| `feedback:handle` | 处理投诉建议（仅 ADMIN 角色，见 V15） | ✓ | | | |
| `classroom:read` | 查询空教室 | ✓ | ✓ | ✓ | ✓ |
| `leave:read_all` | 查询请假情况 | ✓ | ✓ | | ✓ |
| `attendance:manage` | 登记考勤 | ✓ | ✓ | | |
| `attendance:read` | 查看班级课表/考勤 | ✓ | ✓ | | ✓ |

新增内置角色 `STUDENT_UNION`（学生会）：学生能力 + `leave:read_all` + `attendance:read`，
只能看不能改。管理员在「管理 → 用户 → 角色」里给学生会成员分配该角色。

数据归属仍在服务层判定：教师只能改自己任教课程的成绩、只能审自己课程/所带班级的请假；
导员按 `class_counselor` 判定；学生只能看自己的成绩、评自己选过的课。

---

## 4. 成绩与绩点

- 教师按课程录入，先存 `DRAFT`（学生不可见），全部学生都有分数后才允许发布；
- 发布后写入 `published_at`，学生端可见并计入 GPA；已发布成绩教师只读，管理员可改（改后会回到草稿等待重新发布）；
- GPA 按学分加权：`Σ(绩点 × 学分) / Σ学分`；加权平均分同理；
- 绩点规则按分数左闭右开匹配，100 分含在最后一档；默认 4.0 制（90–100→4.0 … 60–64→1.0，<60→0）。

接口：

| 方法 | 路径 | 权限 |
|---|---|---|
| GET | `/api/v1/grades/me` | `grade:read` |
| GET | `/api/v1/grades/rules` | 成绩相关权限 |
| GET | `/api/v1/grades/course/{courseId}` | `grade:manage` / `manage_all` |
| PUT | `/api/v1/grades/course/{courseId}` | 同上（批量保存草稿） |
| POST | `/api/v1/grades/course/{courseId}/publish` | 同上 |

问答页起步面板（「有什么想查」旁）读取 `/grades/me`，展示总 GPA 与已修学分，并给出去成绩页的入口。

---

## 5. 学生评教

- 维度：教学态度、内容与收获、互动答疑、作业考核，各 1~5 分；`total_score` 为四项均值；
- 每名学生每门课每位教师只能提交一次（数据库唯一键兜底）；
- 教师只看到每门课的各维度均值、总分均值与匿名留言（不返回学生身份）；
- 默认匿名，可在提交时取消匿名；默认值来自 `evaluation.anonymous` 设置。

接口：`GET /evaluations/tasks`、`POST /evaluations`、`GET /evaluations/course/{id}`、`GET /evaluations/me`。

---

## 6. 投诉与建议（校长信箱）

- 分类：投诉 / 建议 / 反映情况 / 其他；状态 `OPEN → PROCESSING → RESOLVED/CLOSED`；
- 可匿名：匿名条目即使对处理人也不返回姓名（数据库保留 `student_id` 供必要追溯）；
- **处理反馈仅系统管理员**：需要 `ADMIN` 角色 + `feedback:handle` 权限点双重校验（V15 会清掉非 ADMIN 角色上的该权限点）；
- 全员在「校长信箱 → 投诉与建议」提交并查看自己的记录；管理员在「校长信箱 → 处理反馈」回复，回复内容学生可见。

接口：`POST /feedbacks`、`GET /feedbacks/me`、`GET /feedbacks`（仅 ADMIN）、`GET /feedbacks/{id}`（本人或 ADMIN）、`POST /feedbacks/{id}/reply`（仅 ADMIN）。

---

## 7. 空教室

占用来源取并集，避免两套课表不同步时把有课的教室当空闲：

1. `sch_assignment` 中状态为 `PUBLISHED` 的课表（按 `room_id` 精确占用），结合 `sch_task` 的周次/单双周；
2. `course_schedule` 中课程模块的排课（教室是文本，按编号或名称匹配 `sch_room`）。

流程：按日期解析学期 → 由 `sch_semester_config.first_week_start` 计算教学周 → 过滤当天的占用 →
返回未占用教室。没有登记教室档案时，退化为课程排课里出现过的教室名（容量未知）。

接口：

- `GET /classrooms/free?date&startPeriod&endPeriod&minCapacity&building&roomType`
  不传日期取今天，不传节次取当前节次；
- `GET /classrooms/week?from&startPeriod&endPeriod...` 返回从 `from` 起 7 天每天的空闲数量，用于周视图。

---

## 8. 请假：双审批、销假与查询

### 8.1 流程

设置项（管理 → 设置）：

| 键 | 默认 | 说明 |
|---|---|---|
| `leave.approval.teacher` | true | 是否需要任课教师审批 |
| `leave.approval.counselor` | true | 是否需要班级导员审批 |
| `leave.approval.order` | `TEACHER,COUNSELOR` | 审批顺序，可改为导员先审 |

提交时把「实际生效的顺序」和「级数」快照到 `leave_request.approval_order/total_stages`，
之后改设置不影响已提交的申请。班级没配导员时自动跳过导员环节（级数相应减少），不会卡住学生。

状态机保持 `PENDING → APPROVED/REJECTED`，逐级推进：

```
PENDING(stage=1) ─教师通过→ PENDING(stage=2) ─导员通过→ APPROVED
        └──────任一级驳回──────────────→ REJECTED
        └──────申请人撤销──────────────→ CANCELLED
```

并发控制：每级用 `WHERE status='PENDING' AND current_stage=?` 条件更新，
`leave_approval (leave_id, stage)` 唯一键兜底；同级两人同时操作只有一个成功，另一个返回 409。

### 8.2 销假

通过后、返校时由本人销假：只写 `revoked_at/revoke_note/revoked_by`，不改状态，
用 `AND revoked_at IS NULL` 保证只销一次。销假后仍按请假处理考勤。

### 8.3 查询

`GET /leave-requests/all`（`leave:read_all`）：学生会与管理员看全部，教师看自己任教课程
与所带班级；支持状态、班级、课程、学期筛选与分页。学生会只有读权限，没有审批入口。

### 8.4 导员配置

`GET /leave-requests/counselors`、`PUT /leave-requests/counselors/{classId}`
（均需 `leave:manage_all`）。一个班可配置多名导员，任一人可审批本班请假。

---

## 9. 班级考勤（每班每节课）

- 场次 = 课程 + 行政班 + 日期 + 起止节次；同一班同一节次唯一；
- 建场次时自动把当天与该课程重叠的「已通过请假」标为 `LEAVE`，其余默认 `PRESENT`；
- 教师/导员/管理员可修改，提交（FINAL）后教师不可改、管理员可改；
- 班级课表按「班里学生已选课程」反查，展示每门课的排课节次与已登记/旷课人次；
- 统计按学生汇总出勤、旷课、迟到、请假与旷课率。

接口：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/attendance/classes` | 班级选项 |
| GET | `/attendance/classes/{id}/timetable` | 班级课表 + 旷课汇总 |
| GET | `/attendance/classes/{id}/stats` | 学生考勤统计 |
| GET | `/attendance/sessions?classId&courseId&from&to` | 场次列表 |
| POST | `/attendance/sessions` | 新建场次并生成名单 |
| GET | `/attendance/sessions/{id}` | 场次详情 |
| PUT | `/attendance/sessions/{id}` | 保存明细 |
| POST | `/attendance/sessions/{id}/finalize` | 提交 |

---

## 10. 前端

| 路由 | 页面 | 内容 |
|---|---|---|
| `/ai` | `AiChatView` | 问答 + 办事：起步面板（成绩绩点、待办、快捷入口、猜你想问）、统一消息流、确认单 |
| `/grades` | `GradesView` | 我的成绩 / 评教 / 评教结果 / 成绩录入 |
| `/services` | `ServicesView` | 教室：空教室 / 我的借用 / 借用审批（旧 `?tab=feedback` 链接重定向到信箱） |
| `/mailbox` | `MailboxView` | 校长信箱：投诉与建议（全员）/ 处理反馈（仅系统管理员） |
| `/leave` | `LeaveView` | 我的请假 / 待我审批 / 请假查询 / 班级考勤 / 我的考勤 / 考勤申诉 |
| 顶栏铃铛 | `NotificationDrawer` | 从右侧滑出的通知抽屉：全部/未读/公告、已读跳转、查看全部 |
| `/notifications` | `NotificationView` | 通知中心：全部/未读/公告、已读跳转 |
| `/admin?tab=settings` | `SettingsPanel` | 审批流程、班级导员、考勤预警与通知渠道 |

导航：问答、知识库、论坛、**课程**（课表 + 排课分组）、成绩、请假、教室、**校长信箱**（投诉与建议 + 处理反馈分组）、管理（按权限显示）。
「课程」「校长信箱」为大模块：点击标题展开/收起子项，再点子项进入页面；当前路由所在分组自动展开。
首页与独立办事页已删除：`/` 与 `/act` 都重定向到 `/ai`，旧链接保持可用。

### 10.1 导航与目录

- **主导航在左**：桌面固定 224px 侧栏，字标在上、九个入口竖排（带拉丁短题），底部是账号与通知铃铛；
  正文区 `margin-left: var(--nav-w)`，`--masthead-h` 在桌面为 0，吸附元素直接贴顶。
- **手机**：顶部条（汉堡 + 字标 + 铃铛 + 头像）高 56px；汉堡打开左侧导航抽屉（锁滚动、Esc 关闭、路由切换自动收起）；
  铃铛与头像留在顶部条，通知抽屉与账号操作始终一键可达。账号菜单抽成 `UserMenu.vue`，桌面向上展开、手机向下展开。
- **模块目录在右**：问答的历史会话（`Contents`）、知识库分类、论坛分类都移到模块页右侧，选中态改到右边缘；
  窄屏时问答目录从右滑出、知识库/论坛分类变成标题下方单行横滑。
- **个人资料**：身份卡下面是「基本信息 / 修改密码 / 我发过的帖子」横向页签；帖子列表显示发布时间，
  编辑过的显示「谁（角色）修改于何时」，并可直接编辑与删除。
- **侧栏不投影**：左侧导航与右侧抽屉都不再使用 `box-shadow`，只用 1px 实线分隔边界。

### 10.2 移动端

手机是主要入口，布局按「第一屏能看到内容」重排：

- 报头两行压缩到约 86px，导航横向滚动并在进入页面时自动定位当前入口；
- 页面报头在窄屏隐藏导语与拉丁短题，标题降到 25px，留白收紧；
- 表格放入 `.table-scroll` 容器内横滑，成绩表在手机上隐藏「等级/备注」等次要列；
- 空教室筛选压成「日期一行 + 节次/容量一行」，周视图单行横滑；课表隐藏说明文字、周次单行横滑；
- 请假查询/考勤筛选两列排布，列表与表单改为单列；
- 问答页起步面板压缩为「标题 + 成绩速览 + 待办 + 快捷入口 + 猜你想问」，输入框常驻底部。
- 知识库与论坛按「一屏看更多」重排：手机端目录单行横滑、隐藏页头小标与列表说明/摘要，
  每条只留分类、标题与一行元信息——空间条目从 204px 压到约 83px、帖子从 176px 压到约 87px，
  390×844 下知识库首屏可见 8 个空间、论坛可见 4 封帖子（数据量允许时可到 7 条）。
- 检索在手机端收成一个放大镜（知识库/论坛/空间页），点开才展开输入框并自动聚焦；
  有关键词收起时图标带一个小橙点提示「仍在筛选」。管理端与选课页的检索属于筛选条的一部分，保持常驻。
- 页头标题与主操作并排（`UiPageHeader` 的 `compact`）：书架「新建空间」、来信「写一封」不再单独占一行；
  空间页隐藏竖排小标与长说明，四个操作按钮单行右对齐。
- 论坛分类目录不再显示帖子数（手机上数字占位且意义有限），只保留「公告」标记。

---

## 11. 只读 AI 工具

新增 `CampusServiceToolProvider`：

- `findFreeClassrooms`：查某天某几节的空教室（`classroom:read`）；
- `getMyGrades`：查本人成绩与绩点（`grade:read`）。

两者都是只读，调用即执行；成绩录入、考勤登记等写操作不开放给模型，避免误改教务数据。
AI 工具总数 19 → 21。

---

## 12. 验证记录（2026-10-30，本机 MySQL 8.0）

- Flyway 从 v9 升到 v10 成功，10 个迁移校验通过；
- 端到端脚本（`.localrun/e2e-v10.py`）逐项通过：
  - 学生归班 → 配置导员 → 录成绩 95 分 → 发布 → 学生成绩单 GPA 4.00；
  - 学生评教 4.5 分 → 教师聚合正确；
  - 投诉建议提交 → 管理员回复；
  - 请假提交（两级）→ 教师通过（仍 PENDING）→ 导员通过（APPROVED）→ 学生销假；
  - 考勤：建场次自动标 LEAVE；另一场记 ABSENT 后统计为 1 旷 1 假、旷课率 50%；
  - 空教室当天明细与一周概览返回正常；
- 权限边界：学生访问成绩册/全校请假 403，非任课教师访问成绩册 403；
- 设置约束：同时关闭教师与导员审批返回 400；只留导员时流程解析为单级；
- AI 工具目录 21 个，两个新工具实际调用成功。

---

## 13. 已知限制

- 成绩发布要求全部选课学生都有分数；缓考/免修需要先给一个分数或备注，暂不支持「无成绩」单独发布；
- 绩点规则目前只读，管理员改规则需要直接改 `grade_rule`（接口预留了读取，写入未开放 UI）；
- 空教室依赖教室档案与课表质量：教室未登记时只能按文本匹配，容量/楼栋筛选失效；
- 请假查询在 `toReview` 中先取候选再在内存过滤「轮到我」，待审批量极大时需要改为 SQL 过滤；
- 考勤统计按 `session_date` 落在课程所属学期，跨学期补录需要选对场次日期；
- 学生会角色需要管理员手工分配，首次部署后请先在用户管理里设置。
