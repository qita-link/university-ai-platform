# 05 项目记忆

> 用途：跨会话/跨阶段快速恢复上下文。只记录**已确认的事实、关键决策和下一步**；详细设计见 01–04。每次阶段验收后更新本文件。

最后更新：2026-10-30

## 1. 当前状态

- 阶段一（基础框架）完成：2026-10-26。
- 阶段二（用户与权限）完成：2026-10-27。
- 阶段三（知识库管理）完成：2026-10-27。
- 阶段四（知识库检索与 AI 问答）主体完成：2026-10-27；Qdrant/Embedding 尚未接入。
- 阶段五（论坛）完成：2026-10-27。
- 阶段六（课程、排课、选课与请假）完成：2026-10-27；同时打通知识库 `COURSE` 可见范围。
- 阶段七（AI 工具调用）完成：2026-10-27。
- 阶段八（系统测试与部署文档）完成：2026-10-27；全部八个阶段交付完毕。
- 后续增量（非阻塞）：Qdrant/Embedding 向量检索、前端页面、CI/容器化、监控告警。
- 代码状态：无 Git 仓库；工作目录 `C:\Users\Fruit\Desktop\dschtool`。
- 技术栈：Java 17、Spring Boot 3.4.13、Spring Security + JWT、MyBatis-Plus 3.5.14、MySQL 8、Flyway、Spring AI 1.0.3（默认关闭）、springdoc 2.8.8。

## 2. 已实现功能（阶段二至八）

### 用户与权限
- 认证：`POST /auth/login`、`POST /auth/logout`、`PUT /auth/password`、`GET /auth/me`。
- 用户管理：分页/筛选、创建、修改、启停/锁定、解锁、角色整体替换、重置密码。
- 组织树：创建、修改/移动、启停、树查询、详情。
- `DataScopeService`：组织自身/下级/上级、可管理范围、成员范围、可管理断言。
- 角色与权限：内置 ADMIN/TEACHER/STUDENT、25 个权限点，只读接口。
- 安全与审计：登录锁定、双桶限流、防枚举、token_version 吊销、保护规则、审计事件。

### 知识库
- 分类：两级树、同级重名、删除前检查子分类/空间。
- 空间：`PUBLIC/ORG/PRIVATE`（`COURSE` 预留给阶段六）、owner/成员、软删除、乐观锁。
- 成员：`MANAGER/EDITOR/VIEWER`；owner 不可移除/改角色。
- 文档：Markdown 批量上传（逐文件事务、部分失败可追踪）、在线编辑、版本、软删除、重处理。
- 分块：按标题路径 + 字符数/重叠切分；旧版本块 `STALE`，当前版本 `ACTIVE`。
- 索引：`kb_chunk` 标题 ngram + 正文 ngram；阶段四已实现检索 API。
- 导入：可选启动导入器，按 `source_path + content_hash` 幂等。

### 检索与 AI 问答
- `KnowledgeRetrievalService` 接口；`MysqlKeywordRetrievalService` 为关键词实现，Qdrant 待接入。
- 中文自然语言拆 2 字 ngram，标题分数 ×4，正文/章节 ×1；应用层去重/阈值/snippet。
- `POST /knowledge/search`：返回 chunk/文档/版本/空间、标题、章节、分数、候选数、`insufficientEvidence`。
- `POST /ai/chat`：检索 → 上下文预算 → Spring AI ChatModel → 引用；无依据不调用模型；无模型 503。
- 保存 `ai_conversation/ai_message/ai_retrieval_record/ai_message_citation`；Token REAL/ESTIMATED。
- 会话列表/详情/删除仅限本人；AI 工具调用见阶段七（工具只调用既有 Service，不复制权限规则）。
- 测试：50 单元 + 75 集成 = 125，全部通过。

### 论坛
- `V4__init_forum.sql`：`forum_category`、`forum_post`、`forum_comment`；帖子/评论软删除、帖子 `version`、两级评论 `parent_id/root_id`、ngram 全文索引。
- 分类：扁平结构；管理员创建/修改/停用/启用/删除；停用分类隐藏其下帖子；分类有帖子（含已删除）时不能删除。
- 帖子：发帖、整体修改（乐观锁）、软删除、分页、分类/作者/状态筛选、中文关键词检索；置顶优先、最后活跃时间倒序。
- 评论：顶级评论 + 楼中楼回复；回复回复仍归入同一 root，展示两层；评论线程按顶级评论分页；顶级评论删除级联软删除回复。
- 权限：`forum:post:write`、`forum:comment:write`、`forum:moderate`、`forum:category:manage`；读操作登录即可。
- 并发：评论创建/删除先锁帖子行再维护 `comment_count`，并发评论不丢计数；帖子内容修改用 `version`。
- 检索：复用 `common.util.SearchTerms` 2 字 ngram + `FULLTEXT(title, content)`。
- 审计：分类/帖子/评论的创建、修改、删除、隐藏/恢复/置顶均写 `audit_log`。
- 测试：新增 `ForumCategoryIT` 4、`ForumPostIT` 7、`ForumCommentIT` 5；当前 50 单元 + 91 集成 = 141，全部通过。

### 课程、选课与请假
- `V5__init_course.sql`：`semester`、`course`、`course_teacher`、`course_schedule`、`course_selection`、`leave_request`；容量 `CHECK`、`UNIQUE(semester_id, code)`、选课生成列 `active_key` 唯一约束；并给 `kb_space.scope_course_id` 补课程外键。
- 学期：CRUD（写操作仅 `course:manage_all`）、`GET /semesters/current`、状态机 `DRAFT→ACTIVE→CLOSED`（`CLOSED` 终态）；选课窗口独立于学期起止日期。
- 课程：创建人自动为主讲；`version` 乐观锁；状态机 `DRAFT/OPEN/CLOSED/CANCELLED`，`OPEN` 前必须已排课；停开级联退课 + 清零计数 + 撤销待审请假；`/courses/available` 逐条返回 `selectable` 与 `blockedReason`。
- 授课教师：复合主键 `(course_id, teacher_id)`；`PRIMARY` 唯一、`ASSISTANT` 多名；改主讲需 `course:manage_all`；主讲不能直接移除；新增教师校验其学期内其它课程时间冲突。
- 排课：星期/节次/周次/单双周/教室；冲突三层校验（参数有序、本课程不重叠、教师课表不冲突）；`GET /schedules/me` 合并学生已选与教师任教课程。
- 选课：窗口 + `OPEN` + 容量条件更新防超卖 + 生成列唯一约束防重复 + 时间冲突检测 + 按学生行锁串行化；退课后可重选且计数一致。
- 请假：只能对已选课程提交；时间必须落在学期内；`PENDING→APPROVED/REJECTED`（任课教师/管理员）、`PENDING→CANCELLED`（申请人）；条件更新防并发审批。
- 知识库 `COURSE` 可见范围启用：任课教师与有效选课学生可读，退课立即失效。
- 测试：新增 `SemesterIT` 4、`CourseIT` 6、`CourseScheduleIT` 5、`CourseSelectionIT` 6、`LeaveRequestIT` 6、`CourseKnowledgeIT` 2，单元新增 `ScheduleConflictTest` 10；当前 60 单元 + 120 集成 = 180，全部通过。


## 3. 必须保持的行为（修改代码前先读）

### 登录与令牌
- 连续失败 5 次锁定 15 分钟，可配置；锁定期间不校验密码。
- 用户名不存在也要执行一次 bcrypt 比较（dummy hash），错误码/提示与密码错误一致。
- 登录限流：IP 与 IP+用户名两个桶；用户名转小写；超限 429 + `Retry-After`；限流不写审计。
- 退出、改密、重置密码、停用/锁定都使 `token_version++`；临时锁定**不**吊销令牌。
- 角色变更不吊销令牌；每个请求从数据库重新加载角色/权限。
- 改密成功返回新令牌；失败不吊销旧令牌。

### 用户保护
- 不能对自己停用/锁定、改角色、重置密码。
- 不能停用/锁定最后一个 ACTIVE 管理员，或移除其 ADMIN 角色。
- 操作人必须拥有目标用户的全部权限点；授予的角色权限必须是操作人权限的子集。
- 管理员保护加锁顺序：`lockActiveAdminIds()` → `selectByIdForUpdate(target)`。
- 用户资料更新使用 optimistic lock `version`；登录计数更新不碰 `version`/`updated_at`。
- 禁止 `updateById(entity)` 更新用户，必须使用只改目标列的 SQL。

### 组织
- 类型 rank：COLLEGE(1) < DEPARTMENT(2) < MAJOR(3) < CLASS(4)；下级 rank 必须更大，可跨级。
- 移动上级不能是自己或自己的下级；类型变化后现有下级必须仍合法。
- 停用前全部直接下级必须停用；启用时上级必须启用。
- 所有结构变更先 `lockAll()` 锁整棵树（按 id）。
- 组织不物理删除；停用组织对普通用户表现为 404，`includeDisabled` 需要 `org:manage`。

### 数据权限
- `manageableScope`：有全局权限点 → `all()`；否则本人组织+全部下级；无组织 → `none()`。
- `membershipOrgIds`：本人组织+全部上级；用于“组织 X 及其下级开放”的资源。
- `DataScope.applyTo`：all 不加条件，none 加 `1 = 0`，有限集合加 `IN`。
- 组织状态不参与 DataScope 计算。

### 事务
- `UserAdminService` 使用 `READ_COMMITTED`，原因见 02 文档 4.4。
- 用户管理写操作在 Service 层再次做权限断言（Controller 的 `@PreAuthorize` 只保护 HTTP 入口，AI 工具会复用 Service）。

### 知识库
- 权限判断集中在 `KnowledgeAccessService`，Controller 的 `@PreAuthorize` 只做功能权限第一层。
- 可见范围：PUBLIC 全员；ORG 按 `DataScopeService.isMemberOf`；PRIVATE 仅 owner/成员；无权限统一 404。
- 成员身份不等于功能权限：STUDENT 即使被设为 EDITOR，仍因缺 `kb:document:write` 不能写。
- 文档版本不可变；编辑/删除把旧块 `STALE`；只检索当前版本 `ACTIVE` 块。
- 编辑/删除文档使用 `version` 乐观锁；编辑前 `selectByIdForUpdate` 锁文档行，串行化版本号。
- 批量上传逐文件独立事务；失败也保留 `process_status=FAILED` 文档；批次状态 PARTIAL/FAILED。
- 本地导入只在 `app.knowledge.import.enabled=true` 时运行，默认关闭；不做 HTTP 目录扫描。
- `V2__init_knowledge.sql` 已发布后不要修改；阶段四新增 V3，阶段五新增 V4，后续追加 V5+。

### 检索与 AI
- 检索必须先算可读 `spaceIds`，不能先查全库再让模型/应用过滤。
- 中文长句必须拆 2 字 ngram；直接把整句交给 MySQL ngram 会匹配不到。
- 标题和正文是两个 FULLTEXT 索引，标题权重 ×4；去重按 chunk 内容 SHA-256。
- 默认 `minScore=0.0`：MySQL ngram 绝对分数很小，只过滤非正分；需要严格过滤时请求传 `minScore`。
- 无检索结果时不调用模型，写 `NO_EVIDENCE`；无 ChatModel 时返回 503，写 `FAILED`，不写引用。
- AI 只使用检索到的片段；引用保存 chunk/文档/版本 ID；Token 用量标记 REAL/ESTIMATED。
- `SearchStrategy` 已预留 VECTOR/HYBRID；Qdrant 接入时保持 `KnowledgeRetrievalService` 接口不变。

### 论坛
- 登录即可读分类/帖子/评论；没有 `forum:read` 权限点。无权读取、隐藏、已删除统一 404。
- 帖子状态：`PUBLISHED/HIDDEN/DELETED`；`HIDDEN` 仅作者本人和 `forum:moderate` 可见，作者修改后仍保持隐藏；`DELETED` 只能走 DELETE，不能通过状态接口设置。
- 分类状态：`DISABLED` 后其下帖子对普通用户（含作者）不可见，且不能发帖/改帖到该分类；`includeDisabled=true` 需要 `forum:category:manage`。
- 评论固定两级：顶级评论 `parent_id/root_id` 为空；回复的 `root_id` 指向顶级评论；回复回复仍归入同一 root。删除顶级评论级联软删除全部回复。
- 评论创建/删除先 `SELECT ... FROM forum_post WHERE id=? FOR UPDATE` 锁帖子行，再增减 `comment_count`；不要绕过该锁直接改计数。
- 帖子内容修改用 `version` 乐观锁；评论计数/`last_activity_at` 更新不递增 `version`。
- 分类删除前必须没有任何帖子（含软删除）；软删除帖子保留分类外键用于追溯。
- 中文检索必须先经 `SearchTerms` 拆 2 字 ngram；帖子 FULLTEXT 索引列为 `(title, content)`。
- 版主权限 `forum:moderate` 是全局的，没有按分类的版主表。

### 课程、选课与请假
- 归属判断集中在 `CourseAccessService`：`course:manage_all` 管全部；`course:manage_own` 只能管**本人主讲**课程；任课教师（含助教）可看名单、审批请假，但不能改课程与排课。
- 学期 `CLOSED` 为终态：不能改学期、不能建课、不能排课、不能选课；`CLOSED` 学期仍可读取历史课程。
- 选课必须同时满足：学期 `ACTIVE` + 当前时间在选课窗口内 + 课程 `OPEN` + 未重复 + 未满 + 无时间冲突。退课同样受窗口约束。
- 选课加锁顺序固定为“学生行（`sys_user`）→ 课程行（`course`）”，不要调换；名额占用只允许走 `CourseMapper.incrementSelectedCount`（条件更新），不要先查后改。
- 退课用 `markDropped`（`WHERE status='SELECTED'`）后再 `decrementSelectedCount`；不要直接 `updateById` 改选课状态。
- 停开课程必须走 `changeStatus(CANCELLED)`：它在同一事务内批量退课、清零计数、撤销待审请假；不要只改 `status` 列。
- 排课冲突规则只有一处实现（`ScheduleConflict`）：同一天 + 节次区间相交 + 周次区间相交 + 单双周不互斥。排课与选课都调用它，改规则只改这里。
- 同一课程可有 ODD 与 EVEN 两条同槽位排课；`ALL` 与任一单双周冲突。
- 请假状态变更只允许 `PENDING → APPROVED/REJECTED/CANCELLED`，全部走 `LeaveRequestMapper.decide/cancel` 条件更新；0 行返回 409，不要先查状态再更新。
- 教师只能审批自己任教课程的请假，且不能审批本人提交的申请；管理员凭 `leave:manage_all` 审批全部。
- 知识库 `COURSE` 可见范围依赖 `CourseMembershipService`（任课教师 + 有效选课），知识库模块只能依赖课程模块的 Service，不能反向依赖。
- `V5` 已发布，不要修改；阶段七新增 `V6`。

### AI 工具调用
- 工具只做“参数校验 + 调用 Service”，不要在工具里写业务规则或自行判断归属；否则会绕开阶段二至六的权限与并发保证。
- 写操作工具必须提供 `preview`（目标与影响），确认执行使用 `AiToolCallMapper.claim`（`PENDING_CONFIRM` + `confirm_expires_at > now` → `EXECUTING`），不要先查状态再执行。
- 执行结束必须调用 `finish`（`EXECUTING → SUCCEEDED/FAILED`），失败也要写 `error_code`，不要停留在 `EXECUTING`。
- 工具参数一律经 `AiToolArguments` 校验；不要直接把 `Map` 强转成业务类型，也不要把参数拼进 SQL。
- 新增工具只需实现 `AiToolProvider` 并返回 `SimpleAiTool`，工具名全局唯一（重复会导致启动失败）。
- 后端不解析模型自由文本为工具调用；调用由编排层显式发起，写操作必须经用户确认。
- 工具调用审计 `source=AI_TOOL`，detail 只放工具名/状态/参数摘要，不放正文全文。

### AI 工具调用
- `V6__init_ai_tool.sql`：`ai_tool_call`（参数 JSON 快照、参数摘要、待确认预览、状态、确认截止时间、结果摘要、错误码、延迟、version）。
- 工具框架：`AiTool` + `ToolParameter/ToolPreview/ToolResult` + `AiToolArguments`（参数形状校验）+ `AiToolRegistry` + 5 个 `AiToolProvider`，共 19 个工具。
- 工具清单：知识库 3（检索/列文档/读文档）、课程 6（课表/可选课程/详情 + 建课/改课/排课）、选课 2（选课/退课）、请假 3（我的请假/提交/审批）、论坛 5（检索/发帖/改帖/评论/删帖）。
- 两阶段执行：写操作调用只生成 `PENDING_CONFIRM`（含目标与影响预览、`confirm_expires_at`），确认时条件更新认领为 `EXECUTING` 再执行，终结为 `SUCCEEDED/FAILED`；拒绝为 `REJECTED`，超时为 `EXPIRED`。
- 权限两层：`ai:tool:use` + 工具声明权限（任一），再由业务 Service 判断归属；工具目录按权限过滤。
- 审计：调用/确认/拒绝/过期均写 `audit_log`，`source=AI_TOOL`，`target_type=AI_TOOL_CALL`。
- 记录可见性：本人可查，管理员可查全部并按 `userId` 过滤；越权读取 404。
- 测试：新增 `AiToolArgumentsTest` 8、`AiToolIT` 9；当前 68 单元 + 129 集成 = 197，全部通过。

## 4. 关键文件

| 文件 | 作用 |
|---|---|
| `security/SecurityConfig.java` | 无状态 JWT、公开端点、401/403、CORS、安全头 |
| `security/jwt/JwtTokenService.java` | HS256 签发/校验、`ver` 声明 |
| `auth/service/AuthService.java` | 登录、锁定、防枚举、退出、改密 |
| `auth/service/LoginRateLimiter.java` | 登录/改密进程内限流 |
| `user/service/UserAdminService.java` | 用户管理、保护规则、乐观锁 |
| `user/service/OrgUnitService.java` | 组织树、层级/环/启停约束 |
| `user/datascope/DataScopeService.java` | 数据权限计算 |
| `user/mapper/SysUserMapper.java` | 行锁、登录计数、令牌版本、角色查询 |
| `user/mapper/SysOrgUnitMapper.java` | 整树锁、递归下级/上级查询 |
| `knowledge/service/KnowledgeAccessService.java` | 知识库可见范围/成员/owner 访问判断 |
| `knowledge/service/KnowledgeDocumentService.java` | 上传/编辑/版本/分块/删除/重处理 |
| `knowledge/service/MarkdownChunker.java` | 标题路径 + 长度/重叠分块 |
| `knowledge/importer/KnowledgeImportRunner.java` | 可选启动语料导入 |
| `retrieval/service/MysqlKeywordRetrievalService.java` | MySQL ngram 检索、bigram 查询、标题加权、去重 |
| `common/util/SearchTerms.java` | 知识库/论坛共用的中文 2 字 ngram 查询词构造 |
| `ai/service/AiChatService.java` | 上下文预算、模型调用、引用、会话/Token 持久化 |
| `forum/service/ForumAccessService.java` | 论坛帖子/评论/版主访问判断 |
| `forum/service/ForumPostService.java` | 发帖、修改、软删除、列表/检索、隐藏/恢复/置顶 |
| `forum/service/ForumCommentService.java` | 两级评论、线程分页、级联删除、评论计数 |
| `forum/service/ForumCategoryService.java` | 论坛分类管理与停用语义 |
| `forum/mapper/ForumPostMapper.java` | 帖子分页/全文检索、评论计数、分类帖子数 |
| `course/service/CourseAccessService.java` | 课程数据归属：主讲/任课教师/管理员判定 |
| `course/service/CourseService.java` | 课程 CRUD、状态机、可选课程、授课教师、停开级联 |
| `course/service/CourseScheduleService.java` | 排课 CRUD、教师课表冲突、我的课表 |
| `course/service/CourseSelectionService.java` | 选课/退课、容量与时间冲突、名单 |
| `course/service/LeaveRequestService.java` | 请假提交、待审、审批/驳回/撤销 |
| `course/service/ScheduleConflict.java` | 排课时间冲突纯函数（单元测试覆盖） |
| `course/service/CourseMembershipService.java` | 任课教师 + 已选学生，供知识库 `COURSE` 可见范围 |
| `ai/service/AiToolService.java` | 工具目录、调用、两阶段确认/拒绝、记录查询、AI_TOOL 审计 |
| `ai/tool/AiToolRegistry.java` | 工具注册表（启动校验工具名唯一） |
| `ai/tool/AiToolArguments.java` | 工具参数形状校验（必填/类型/长度/区间/枚举/时间） |
| `ai/tool/*ToolProvider.java` | 知识库/课程/选课/请假/论坛五组工具 |
| `ai/mapper/AiToolCallMapper.java` | 工具调用状态条件更新（认领/终结/拒绝/过期） |
| `db/migration/V1__init_system.sql` | 系统表与种子数据 |
| `db/migration/V2__init_knowledge.sql` | 知识库表、索引、约束 |
| `db/migration/V3__init_ai_and_search.sql` | 标题索引 + AI 对话/检索/引用表 |
| `db/migration/V4__init_forum.sql` | 论坛分类、帖子、两级评论、ngram 索引 |
| `db/migration/V5__init_course.sql` | 学期、课程、授课教师、排课、选课、请假 + 知识库课程外键 |
| `db/migration/V6__init_ai_tool.sql` | AI 工具调用记录表 |
| `docs/01-development-plan.md` | 阶段状态与验证记录 |
| `docs/04-api-design.md` | 已实现接口、错误码、访问矩阵 |

## 5. 测试与构建

### 命令
```bash
# 单元测试
mvn test

# 全量（需要 MySQL 8 测试库；会 clean + migrate）
mvn verify -Pit
```

测试库默认 `localhost:3306/campus_ai_test`，账号 `campus/campus_dev_pwd`，可用 `TEST_DB_URL`、`TEST_DB_USERNAME`、`TEST_DB_PASSWORD` 覆盖。

### 测试分布
- 单元 68：`SensitiveDataMaskerTest` 5、`JwtPropertiesTest` 3、`GlobalExceptionHandlerTest` 7、`TokenBucketRateLimiterTest` 4、`PasswordPolicyTest` 12、`LoginRateLimiterTest` 3、`OrgTreeUtilTest` 4、`DataScopeTest` 5、`MarkdownChunkerTest` 4、`MysqlKeywordRetrievalServiceTest` 3、`ScheduleConflictTest` 10、`AiToolArgumentsTest` 8。
- 集成 137：`FoundationIT` 16、`AuthIT` 14、`UserAdminIT` 12、`OrgUnitIT` 6、`DataScopeIT` 4、`AccessMatrixIT` 3、`PrivilegeBoundaryIT` 2、`KnowledgeSpaceIT` 4、`KnowledgeDocumentIT` 5、`KnowledgeImportIT` 1、`KnowledgeSearchIT` 4、`AiChatIT` 2、`AiDisabledIT` 1、`SpringAiCompatibilityIT` 1、`ForumCategoryIT` 4、`ForumPostIT` 7、`ForumCommentIT` 5、`SemesterIT` 4、`CourseIT` 6、`CourseScheduleIT` 5、`CourseSelectionIT` 6、`LeaveRequestIT` 6、`CourseKnowledgeIT` 2、`AiToolIT` 9、`SystemFlowIT` 2、`ConcurrencyIT` 3、`FailureModeIT` 2、`AccessMatrixCourseToolIT` 1。
- 合计 205（68 单元 + 137 集成），失败 0、错误 0、跳过 0。
- 测试类共享 Spring 上下文和数据库；测试数据必须使用随机后缀，不要依赖全表计数。
- `IntegrationTestBase` 在测试上下文启动时 Flyway clean + migrate；仅 `test` profile 允许 clean。
- `CourseTestSupport` 提供建学期/建课/排课/开放/选课/请假的公共操作；学期日期以“今天”为中心，选课窗口默认前后各一天。
- `pom.xml` 为 surefire/failsafe 设置 `-Djdk.attach.allowAttachSelf=true`，兼容禁止外部进程自附加的沙箱/CI（Mockito 5 inline mock maker）。
- 受限沙箱里 surefire 可能无法在系统临时目录建目录，需把 `TMP`/`TEMP` 指向可写目录（本机用 `target/tmp`）。

### 验证环境（2026-10-27）
- JDK 17.0.20.1（Temurin），Maven 3.10.0，MySQL 8.0.46。
- `mvn clean verify -Pit`：68 + 137 通过，`BUILD SUCCESS`。
- jar 冒烟与脚本验证在 `127.0.0.1:3307/campus_ai_smoke` 上完成，服务端口 18080；Windows 沙箱下 Tomcat 需要可写临时目录，启动时加 `-Djava.io.tmpdir=<可写目录>`（生产环境无此限制）。
- 本机验证使用 `.cache/mysql-data` 隔离实例（`127.0.0.1:3307`）与 `.cache/m2` 离线仓库；默认测试库配置仍为 `localhost:3306/campus_ai_test`，可用 `TEST_DB_*` 覆盖。
- 打包冒烟（阶段八，最新）：全新库 `campus_ai_smoke` 启动 jar，Flyway 依次应用 V1–V6（`now at version v6`）、初始管理员创建、`scripts/smoke.sh` 五步全通过（健康检查/登录/工具目录/只读工具/写工具确认闸门）、日志中无管理员密码与 JWT 密钥、冒烟后 `forum_category`/`forum_post` 为 0 条；`scripts/backup.sh` 备份成功（9.5K、29 个 CREATE TABLE、gzip 校验通过）。
- 真实语料导入：`C:\Users\Fruit\Desktop\docs` 66 个 Markdown，导入 56、跳过 10 个空页面、失败 0；56 版本、509 chunks；重启后全部跳过（幂等）。
- 全文索引：`MATCH(heading_path, content) AGAINST ('报到' IN BOOLEAN MODE)` 命中 11 个 chunk。
- AI 测试用本地假 ChatModel 验证引用/Token/会话；未配置模型的 503 路径由 `AiDisabledIT` 验证。
- 真实语料检索冒烟：在 56 篇/509 chunk 上查询“报到需要带什么？”，返回 3 条，首条《入学准备》；未配置模型时 `/ai/chat` 返回 503。
- 论坛：V4 在空库 clean + migrate 成功；16 个论坛集成用例覆盖分类停用、帖子权限/乐观锁/软删除/置顶、中文关键词检索、两级评论、级联删除、审计和 6 并发评论计数。
- AI 工具：V6 在空库 clean + migrate 成功；只读工具立即执行并留痕、写工具需确认、拒绝不执行、过期 409、4 并发确认只成功 1 个、业务规则在确认阶段仍生效、`source=AI_TOOL` 审计可按 `targetId` 查询。
- 课程：V5 在空库 clean + migrate 成功（生成列 `active_key`、`CHECK(selected_count <= capacity)`、课程外键均生效）；**8 并发选课容量 3 恰好 3 成功**；**教师与管理员并发审批同一请假恰好 1 成功 1 冲突**；`COURSE` 知识库在退课后立即失去访问权。
- 本机沙箱的临时工具在 `.cache/`（已 gitignore）：JDK、Maven 本地仓库、隔离 MySQL 数据目录。不要把 `.cache/` 提交或复制到交付物。

## 6. 已知限制 / 待办

- 限流是单实例进程内令牌桶；多实例部署每个实例独立计数。需要全局限流时再接 Redis。
- 角色/权限点只能由迁移脚本维护，没有在线编辑。
- 接口文档以 Swagger 为准，前端在 `frontend/`（Vue 3 + TS），未覆盖的管理项仍走 Swagger。
- 无密码历史、强制改密周期、MFA。
- 知识库只建立 ngram 关键词索引；Embedding/Qdrant 未接入，`SearchStrategy` 已预留 VECTOR/HYBRID。
- AI 问答依赖 `AI_CHAT_PROVIDER`/API Key；未配置时明确 503，不伪造回答。
- 只解析 Markdown 文本，不处理 PDF/Word/图片 OCR。
- 论坛版主权限是全局 `forum:moderate`，没有按分类的版主授权、举报/审核队列、敏感词过滤。
- 论坛评论不支持编辑、点赞、收藏、@提醒、通知；帖子没有浏览数/热度统计和附件上传。
- 分类停用会隐藏其下全部帖子；软删除帖子仍占用分类（分类删除前要求无任何帖子）。
- 选课没有抽签/志愿优先级、候补队列、学分上限与先修课约束；先到先得。
- 排课只校验课程自身与教师课表冲突，没有教室资源建模（同一教室同一时段可能被两门课占用）。
- 请假没有考勤/成绩联动、销假流程、附件与额度控制；请假时间只校验落在学期范围内。
- 课程只停开不物理删除；`CLOSED` 学期仍可读取课程与名单，没有独立的归档快照。
- 教师账号没有“任教班级/学生名单导入”能力，学生名单完全来自选课。
- AI 工具没有模型侧 function calling 编排：后端只提供工具目录与调用/确认 API，由编排层决定调用哪个工具；不解析模型自由文本。
- 没有对话内自动工具循环（一次问答内多次工具调用与结果回填）、工具计费/配额、工具级限流与执行超时。
- `EXECUTING` 若因进程崩溃长期停留，需要人工按 `executed_at` 判断，没有定时清理任务。
- 没有压力/容量测试（长稳压测）、混沌工程与多节点一致性测试；并发用例均为单 JVM + 单库。
- 没有 CI 流水线与容器化产物（Dockerfile/K8s）；构建与部署命令目前是手工 Maven + jar。
- 没有接入 Prometheus/Grafana 指标与告警；健康检查只返回 UP/DOWN，不含依赖细节。
- 前端已覆盖主要业务页，少量管理项仍通过 REST + Swagger 操作。
- `V1`–`V6` 已发布，不要修改；后续增量按 `V7+` 追加。

## 7. 阶段八完成记录与后续增量

### 已交付（2026-10-27）
- 系统测试：`SystemFlowIT`（全链路 2 个）、`ConcurrencyIT`（混合并发 3 个）、`FailureModeIT`（故障与错误映射 2 个）、`AccessMatrixCourseToolIT`（课程与工具权限矩阵 1 个）。
- 部署文档 `docs/06-deployment.md`：前置条件、构建、数据库初始化、环境变量清单、Flyway 升级、Nginx/HTTPS、日志与审计、备份恢复、健康检查、冒烟、故障排查、上线清单。
- 运维脚本：`scripts/init-db.sql`、`scripts/backup.sh`、`scripts/smoke.sh`（均已实跑验证）。
- 打包冒烟：全新库 + jar + `scripts/smoke.sh` 全通过。

### 后续增量（按优先级）
1. **Qdrant/Embedding 向量检索**：实现 `KnowledgeRetrievalService` 的向量/混合实现，`SearchStrategy` 已预留；`kb_chunk.embedding_status/vector_id` 已预留字段；文档删除时需同步删除向量点（失败记录补偿）。
2. **前端**：登录/知识库/论坛/课程/工具确认页；工具确认页直接消费 `/ai/tool-calls/{id}` 的 `preview`。
3. **CI/容器化**：`mvn verify -Pit` 纳入流水线（注意 `-Djdk.attach.allowAttachSelf=true` 与可写临时目录），提供 Dockerfile 与部署清单。
4. **可观测性**：暴露 Micrometer 指标（登录失败、429、AI 503、工具 FAILED、慢请求），接告警。
5. **模型侧编排**：在编排层实现 function calling，用 `/ai/tools` 生成工具清单、用 `/ai/tool-calls` 执行与确认。
6. **压力测试**：选课抢课、论坛热点帖、AI 问答长稳；确认多实例下的限流与连接池表现。

### 维护约定
- 新增业务变更 → 追加 `V7+` 迁移，不改已发布脚本；同步更新 01–06 与本文件。
- 新增工具 → 实现 `AiToolProvider` 并保持“只调用 Service”；同步更新 04 的工具清单与 05 的行为约束。
- 每次阶段验收记录真实测试数量、验证环境与冒烟结果。

## 8. 近期增量（V10–V14，2026-10-28 至 10-30）

前端已落地（Vue 3 + TS，`frontend/`），后端新增 `grade`/`evaluation`/`feedback`/`classroom`/`attendance`/`setting`/`notification`/`profile` 包；
详细接口见 `docs/04-api-design.md`，专题文档见 `docs/14`、`15`、`16`，下面只记**改代码前必须知道**的约束。

### 必须保持的行为
- **成绩与绩点**：成绩点对点（课程×学生）；绩点由分数换算，重修取最高分；学生只看到本人，教师只看到所授课程，学生会/管理员按组织范围。
- **评教**：一学生对同一课程同一教师只评一次（`upsert`），提交后不可改；教师看聚合不看个人；未评教不能选课（开关控制）。
- **请假双审批**：上课教师与辅导员都要同意才算通过，任一驳回即驳回；审批人可配置；销假按「班级 + 节次」粒度。
- **考勤**：旷课记录按班级课表节次生成，支持申诉；缺勤达阈值触发预警并写站内信；申诉审批只允许待审状态流转一次。
- **通知**：`NotificationWriter` 用 `REQUIRES_NEW`，保证业务回滚后站内信仍在；广播走一条 `INSERT…SELECT`（排除发布者）；渠道发送器（Email/Wechat）是占位实现，状态 `SKIPPED`，不实际外发。
- **置顶**：`forum_post.pinned_until` 到期自动失效，排序 SQL 与 Java 都判「pinned 且未到期」；只改状态不重置期限，不做定时清理。
- **公告**：公告分类发帖/改帖需 `forum:announce`；发布即广播 + `popupRequired`；弹窗关闭即已读（`GET /notifications/popups` 最多 5 条）。
- **头像**：存 `user_avatar` 的 MEDIUMBLOB，`sys_user.avatar_version` 自增做缓存失效；`GET /profile/avatar/{userId}` 需登录且带 Bearer，前端用 `objectURL` 按「用户 + 版本」缓存，不能裸写 `<img src>`。
- **帖子编辑痕迹**：`ProfilePostView.editedAt/editorName/editorRole` 用 `version > 0` 判定被改过（评论回帖会刷新 `updated_at`，不能用它判断）；冲突返回 409，前端带 `version` 重试。

### 前端布局约定
- 主导航在**左侧**（桌面常驻 224px，≤900px 收成顶部条 + 汉堡抽屉）；模块目录（问答/知识库/论坛）在**右侧**。
- 抽屉一律 Teleport 到 body：锁 `body` 滚动、Esc 关闭、遮罩淡入；导航抽屉与通知抽屉**没有阴影**，只有 1px 边框。
- `base.css` 的全局规则要盖住页面 scoped 样式，必须加 `body` 前缀提高权重。
- `.rail` 在窄屏要显式 `position: static`，否则 sticky 的 `top` 会残留下移压住页头。
- 窄屏检索收成图标（`.search--collapsible`），640px 以下隐藏摘要与竖排小标以提升信息密度。

### 验证环境（2026-10-30）
- 后端 `java -jar` 在 8080（`/actuator/health` 200），前端 dev server 5173；数据库为本机 `campus_ai`，Flyway 已到 V15。
- 演示账号：`admin / Campus@2026!Admin`、`20260001 / Campus@2026!Stu`、`T047 / Campus@2026!Tea`。
- `mvn -DskipTests package`、`vue-tsc -b`、`npm run build` 均通过；390×844 与 1366×860 截图无横向溢出。

## 9. 导航重构与校长信箱（V15，2026-10-30）

- **导航分组**：`nav.ts` 的 `NAV` 支持 `type:'group'`；「课程」= 课表(`/courses`) + 排课(`/scheduling`)，「校长信箱」= 投诉与建议(`/mailbox?tab=feedback`) + 处理反馈(`/mailbox?tab=handle`)。
  点击组标题展开/收起（Transition 下拉），当前路由所在分组自动展开；子项入口带 `?tab=` 时 `isActive` 会同时匹配查询参数。
- **改名**：原「服务台/服务」改「教室」（`/services` 只留空教室、我的借用、借用审批），原「课程」入口改「课表」；旧的 `/services?tab=feedback|handle` 在路由 `beforeEnter` 重定向到 `/mailbox`。
- **校长信箱**：新 `MailboxView.vue`；投诉与建议全员可见，处理反馈页签/菜单要求 `roles:['ADMIN']` + `feedback:handle`。
- **后端（V15）**：`feedback:submit` 补齐到所有现有角色（全员可提交），非 ADMIN 角色上的 `feedback:handle` 被清除，权限点 module 改为 `mailbox`；
  处理接口 `@PreAuthorize("hasRole('ADMIN') and hasAuthority('feedback:handle')")`，Service 里再用 `user.isAdmin()` 兜底——将来误把权限点分给其他角色也进不来。
- 复测：admin `GET /feedbacks` 200，student/teacher 403；三者 `feedback:submit` 均为 true；非管理员访问 `?tab=handle` 会被前端改回 `?tab=feedback`。
