# 01 开发计划与进度

本项目按阶段推进。每个阶段完成后都要通过编译和自动化测试，并在本文档中更新状态，然后再进入下一阶段。状态只记录**实际验证过**的结果。

## 阶段总览

| 阶段 | 内容 | 状态 |
|---|---|---|
| 一 | 项目分析与基础框架 | ✅ 完成（2026-10-26） |
| 二 | 用户与权限 | ✅ 完成（2026-10-27） |
| 三 | 知识库管理 | ✅ 完成（2026-10-27） |
| 四 | 知识库检索与问答（关键词检索 + AI 引用问答；Qdrant/Embedding 后续） | ✅ 主体完成（2026-10-27）；向量检索待接入 |
| 五 | 论坛（分类、帖子、两级评论、检索、版主管理） | ✅ 完成（2026-10-27） |
| 六 | 课程、排课、选课与请假（含知识库 `COURSE` 可见范围） | ✅ 完成（2026-10-27） |
| 七 | AI 工具调用（工具注册、两阶段确认、执行与审计） | ✅ 完成（2026-10-27） |
| 八 | 系统测试与部署文档（全链路/并发/故障、部署与运维） | ✅ 完成（2026-10-27） |

## 阶段一：基础框架（已完成）

**已交付**
- Maven 工程（Boot 3.4.13 / Spring AI 1.0.3 / MyBatis-Plus 3.5.14 / springdoc 2.8.8 / Flyway），版本兼容性结论见 `02-architecture.md` 第 2 节。
- 配置：`application.yml`，所有敏感项都从环境变量读取；提供 `.env.example`、`config/application-local.yml.example`，`.gitignore` 已排除秘密信息文件。
- Flyway `V1__init_system.sql`：组织单元、用户、角色、权限、关联表、审计日志，以及内置的 3 个角色和 25 个权限点。
- 通用层：`ApiResponse`、`PageQuery/PageResult`（单页上限 100）、`ErrorCode`/`BusinessException`、`GlobalExceptionHandler`（不泄露内部细节）、traceId 过滤器和访问日志、日志脱敏、实体基类与时间/操作人自动填充、统一 `Clock`。
- 安全：无状态 JWT（HS256）签发与校验；每个请求从数据库加载用户状态、角色和权限；通过 token_version 实现令牌吊销；401/403 返回 JSON；`@EnableMethodSecurity`；CORS 白名单；安全响应头；bcrypt。
- 审计：`AuditService`（独立事务、脱敏、截断、写入失败不影响业务）和管理员查询接口 `GET /api/v1/audit-logs`。
- 初始管理员引导：从环境变量读取，只在不存在可用管理员时创建，且不会使用默认密码。
- 接口：`GET /api/v1/system/info`、`GET /api/v1/auth/me`、`GET /api/v1/audit-logs`、Swagger UI、健康检查。

**已验证**（真实 MySQL 8.4.10）
- 单元测试 15 个（阶段一结束时；阶段二完成后总数为 43）：异常处理响应格式与信息不泄露、脱敏规则、JWT 配置校验。
- 集成测试 17 个（阶段一结束时；阶段二完成后总数为 58）：Spring AI 1.0.3 的 OpenAI 自动配置可在 Boot 3.4.13 上正常装配（只创建 Bean，不调用模型）、迁移与种子数据、管理员引导、公开接口与 Swagger、traceId 传递与注入防护、无令牌/伪造/过期令牌返回 401、token_version 吊销与停用账号、角色变更即时生效、`audit:read` 授权（学生/教师 403，管理员 200）、分页参数校验、审计在业务回滚后仍保留且已脱敏、过滤与分页、会话时区。
- 打包后的 jar 冒烟测试：未配置 JWT 密钥时拒绝启动；正常启动后迁移成功、管理员已创建（bcrypt）、日志中不出现初始密码、`/actuator/env` 不对外暴露。

**未包含**（按计划放在后续阶段）：登录接口、用户管理接口、知识库及之后的全部业务模块。

## 阶段二：用户与权限（已完成）

**已交付**
- 认证：`POST /auth/login`、`POST /auth/logout`、`PUT /auth/password`、`GET /auth/me`。
  - 登录成功返回 JWT 与当前用户角色/权限；`Cache-Control: no-store`。
  - 连续失败 5 次锁定 15 分钟（`APP_LOGIN_MAX_FAILED_ATTEMPTS` / `APP_LOGIN_LOCK_DURATION` 可配置）；锁定期间不再校验密码。
  - 用户名不存在时执行一次等时 bcrypt 比较，错误码与提示和密码错误一致，降低账号枚举风险。
  - 登录按“IP”和“IP + 用户名”双桶限流，改密按用户限流；进程内令牌桶，不引入 Redis。超限返回 429 + `Retry-After`。
  - 成功/失败/临时锁定/退出/改密均写审计；日志与审计 detail 不记录密码。
- 令牌吊销：退出、改密、管理员重置密码、停用/锁定账号时 `token_version` 自增，旧令牌立即失效；角色变更无需重新签发令牌，因为每个请求都从数据库加载权限。
- 用户管理（管理员）：分页/关键字/状态/角色/组织子树筛选、创建、修改资料、启停/锁定、解锁、整体替换角色、重置密码。
- 角色与权限：`GET /roles`、`GET /permissions` 只读；内置 ADMIN/TEACHER/STUDENT 与 25 个权限点由迁移脚本维护。
- 组织树：创建、修改（含移动上级）、启停、树查询、详情；类型层级（COLLEGE > DEPARTMENT > MAJOR > CLASS，可跨级）从结构上防止环，移动时另有显式环检测；停用前必须停用全部下级，启用时上级必须启用；不物理删除。
- 数据权限：`DataScopeService` 提供 `orgAndDescendants`、`orgAndAncestors`、`manageableScope`、`membershipOrgIds`、`isMemberOf`、`requireManageable`，并配套 `DataScope`（all/none/组织集合、`applyTo` SQL 条件）。
- 保护规则：
  - 不能停用/锁定自己、修改自己的角色或重置自己的密码；
  - 停用/锁定管理员或移除其 ADMIN 角色前，锁定全部可用管理员行并重新计数，保证至少保留一个可用管理员；并发互相停用/降级只会成功一方；
  - 操作人必须拥有目标用户的全部权限点，且只能授予不超过自身权限的角色；
  - 组织变更锁定整棵树串行执行；用户资料使用乐观锁 `version`。
- 事务与并发：用户管理使用 `READ_COMMITTED`，避免加锁后的普通 SELECT 读到旧快照；管理员保护统一“先锁管理员集合、再锁目标用户行”；登录计数更新不修改 `version`/`updated_at`，不会打断管理员编辑。

**测试与验证**

| 类别 | 数量 | 覆盖 |
|---|---:|---|
| 单元测试 | 43 | 脱敏、JWT 配置、异常响应、令牌桶、密码策略、登录限流、组织树工具、DataScope |
| 集成测试 | 58 | FoundationIT 16、AuthIT 14、UserAdminIT 12、OrgUnitIT 6、DataScopeIT 4、AccessMatrixIT 3、PrivilegeBoundaryIT 2、SpringAiCompatibilityIT 1 |
| 合计 | 101 | 失败 0、错误 0、跳过 0 |

验证环境与结果（2026-10-27）：
- JDK 17.0.20.1（Temurin）、Maven 3.9.16、MySQL 8.0.46；Flyway `V1` 在空库上 clean + migrate 成功。
- `mvn clean verify -Pit`（测试库 `campus_ai_test`）通过：43 单元 + 58 集成，`BUILD SUCCESS`。
- 访问矩阵：学生/教师访问用户、角色、权限、审计、组织写接口均返回 403；管理员对应接口返回 200；未登录返回 401；无角色用户只能访问公共读接口。
- 并发用例：同一 `version` 的 6 个并发修改只有 1 个 200，其余 409；两个管理员并发互相停用/降级后仍保留 1 个可用管理员；8 个并发错误密码不会丢计数或超计数，最终恰好 5 且只触发一次临时锁定。
- 打包冒烟：`target/campus-ai-platform-0.1.0-SNAPSHOT.jar` 在全新 `campus_ai_smoke` 库启动成功；Flyway 建表、管理员引导、登录、创建组织/用户、学生访问 `/users` 返回 403、响应不包含 `passwordHash`，均符合预期。
- 测试 JVM 在 `pom.xml` 中设置 `-Djdk.attach.allowAttachSelf=true`，避免受限沙箱/CI 中 Mockito 无法自附加导致测试初始化失败。

**未包含**
- 前端页面；阶段二只交付后端接口。
- 在线编辑角色/权限点（当前由迁移脚本维护，接口只读）。
- 密码历史、强制改密周期、MFA、短信/邮箱验证。
- 分布式限流/集中会话（当前为单实例进程内令牌桶，多实例各自计数）。

## 阶段三：知识库管理（已完成）

**已交付**
- `V2__init_knowledge.sql`：`kb_category`、`kb_space`、`kb_space_member`、`kb_upload_batch`、`kb_document`、`kb_document_version`、`kb_chunk`；含组织范围外键、乐观锁字段、文档来源幂等键和 ngram 全文索引。
- 分类：树形（最多两级）、同级重名校验、删除前检查子分类/空间；管理员维护，所有 `kb:space:read` 用户可读。
- 空间：创建/查询/修改/软删除；`PUBLIC/ORG/PRIVATE` 可见范围；`COURSE` 预留给阶段六；所有者/成员管理；乐观锁 `version`。
- 成员授权：`MANAGER/EDITOR/VIEWER`；所有者不可被移除或改角色；成员必须再具备对应功能权限才能读/写。
- 文档：Markdown 批量上传（逐文件事务、部分成功、失败文档保留错误）、在线新建/编辑、版本列表/详情、软删除、重新切分。
- 分块：按标题层级和可配置字符数/重叠切分，保存 `heading_path`、字符数、Token 估算；旧版本块标记 `STALE`，仅 `ACTIVE` 块参与检索。
- 本地语料导入：可选的启动导入器，按 `source_path + content_hash` 幂等，内容变化生成新版本；默认关闭，支持配置目录/分类/空间/owner。
- 权限：`kb:category:manage`、`kb:space:create`、`kb:space:manage_all`、`kb:space:read`、`kb:document:write`；组织范围统一复用 `DataScopeService`。
- 审计：分类/空间/成员/文档/批次/导入操作均记录；detail 不保存 Markdown 全文或敏感信息。

**测试与验证**

| 类别 | 数量 | 覆盖 |
|---|---:|---|
| 单元测试 | 47 | 阶段二 43 + `MarkdownChunkerTest` 4（标题路径、长度/重叠、Markdown 清理、空文档） |
| 集成测试 | 68 | 阶段二 58 + `KnowledgeSpaceIT` 4 + `KnowledgeDocumentIT` 5 + `KnowledgeImportIT` 1 |
| 合计 | 115 | 失败 0、错误 0、跳过 0 |

验证环境与结果（2026-10-27）：
- JDK 17.0.20.1（Temurin）、Maven 3.9.16、MySQL 8.0.46；Flyway 在空库上依次执行 V1、V2 成功。
- `mvn clean verify -Pit`：47 单元 + 68 集成，`BUILD SUCCESS`。
- 知识库权限：学院组织范围可读、跨组织不可见；教师不能创建 PUBLIC；课程可见范围在阶段六前返回 422；学生可读公开库但写文档返回 403。
- 批量上传：1 个合法 `.md` + 1 个非法 `.txt` 返回 `PARTIAL`，成功文档 `INDEXED`，失败文档保留 `FAILED` 与错误信息。
- 版本/分块：编辑生成版本 2，旧块 `STALE`、新块 `ACTIVE`；旧 version 再提交返回 409；重处理不新增版本；ngram `MATCH ... AGAINST` 中文查询可命中。
- 真实语料导入：扫描 `C:\Users\Fruit\Desktop\docs` 的 66 个 Markdown，导入 56 个、跳过 10 个空页面、失败 0 个；生成 56 个版本、509 个 chunk；重启后 66 个全部跳过（幂等）。
- 通过 API 验证公开库：管理员/学生均可读取 56 篇文档；学生 `POST` 文档返回 403；样本 `报到` 全文检索命中 11 个 chunk。

**未包含**
- 阶段四的检索排序、上下文预算、AI 问答与引用；当前只建立数据与 ngram 索引。
- Qdrant/Embedding；`kb_chunk.embedding_status/vector_id` 已预留。
- `COURSE` 可见范围的课程成员计算，依赖阶段六课程模块。
- 图片 OCR、PDF/Word 解析；当前只接受 Markdown 文本。

## 阶段四：知识库检索与问答（关键词 + AI 已完成，向量检索待接入）

**已交付**
- `V3__init_ai_and_search.sql`：为 `kb_chunk` 增加 `document_title` 和标题 ngram 索引；新增 `ai_conversation`、`ai_message`、`ai_retrieval_record`、`ai_message_citation`。
- `KnowledgeRetrievalService` 接口与 `MysqlKeywordRetrievalService`：先算可读空间，再在 SQL 内检索；标题 FULLTEXT 分数 ×4 + 正文/章节 FULLTEXT；中文自然语言拆 2 字 ngram；应用层去重、分数阈值、snippet。
- `POST /api/v1/knowledge/search`：返回 chunk/文档/版本/空间、标题、章节、片段、分数、候选数和 `insufficientEvidence`。
- 上下文预算：按 `app.ai.max-context-tokens` 选择片段；最多 `app.ai.top-k` 个；历史消息和问题长度受控。
- `POST /api/v1/ai/chat`：检索 → 提示词 → Spring AI ChatModel → 回答与引用；无依据时**不调用模型**并明确提示；无模型时返回 503 且不伪造答案。
- 会话：`GET /api/v1/ai/conversations`、`GET/DELETE /api/v1/ai/conversations/{id}`；保存 USER/ASSISTANT 消息、检索记录、引用快照和 Token 用量（REAL/ESTIMATED）。
- 引用：回答返回文档标题、章节、文档/版本/chunk ID、片段、分数和序号；对话详情可回看。
- 测试：中文自然语言查询词、标题加权、去重、权限过滤、无依据、模型不可用、引用与 Token。

**测试与验证**

| 类别 | 数量 | 覆盖 |
|---|---:|---|
| 单元测试 | 50 | 阶段三 47 + `MysqlKeywordRetrievalServiceTest` 3（中文 bigram、操作符清理、空查询） |
| 集成测试 | 75 | 阶段三 68 + `KnowledgeSearchIT` 4 + `AiChatIT` 2 + `AiDisabledIT` 1 |
| 合计 | 125 | 失败 0、错误 0、跳过 0 |

验证环境与结果（2026-10-27）：
- JDK 17.0.20.1、Maven 3.9.16、MySQL 8.0.46；Flyway 在空库上执行 V1/V2/V3 成功。
- `mvn clean verify -Pit`：50 单元 + 75 集成，`BUILD SUCCESS`。
- 关键词检索：自然语言“报到需要带什么？”可召回相关片段；标题命中文档排在仅正文命中文档之前；重复内容只返回一条；私有空间在加入成员前不可检索。
- AI 问答：用本地假 ChatModel 验证回答、引用、Token REAL、会话持久化与追问；无依据查询不调用模型；未配置模型返回 503 且保留失败消息、不写引用。
- 历史/权限：会话只能本人访问；未登录 401，无 `ai:chat` 权限 403。
- 真实语料检索冒烟：在 56 篇/509 chunk 的 SJTU Wiki 语料上查询“报到需要带什么？”，返回 3 条，首条为《入学准备》；同一环境未配置模型时 `/ai/chat` 返回 503。

**未包含**
- Embedding 与 Qdrant：`SearchStrategy` 已预留 VECTOR/HYBRID，`KnowledgeRetrievalService` 接口不变；当前只有 `KEYWORD` 实现。
- 流式输出、多轮摘要压缩、回答反馈评分（AI 工具调用已在阶段七完成）。
- 图片 OCR、PDF/Word 解析。
- `COURSE` 可见范围仍依赖阶段六课程模块。

## 阶段五：论坛（已完成）

**已交付**
- `V4__init_forum.sql`：`forum_category`、`forum_post`、`forum_comment`；帖子/评论软删除字段、乐观锁、两级评论 `parent_id/root_id`、帖子 `FULLTEXT(title, content) WITH PARSER ngram`，以及分类/作者/活跃时间/评论线程索引。
- 分类：扁平分类的创建、修改（含停用/启用）、删除（分类下有帖子时拒绝）、列表与详情；`postCount` 只统计已发布帖子。停用分类后，其下帖子对普通用户不可见，也不能继续发帖。
- 帖子：发帖、整体修改（`version` 乐观锁）、软删除、列表分页、按分类/作者/状态筛选、中文关键词检索；置顶帖优先，其次按最后活跃时间倒序。
- 版主管理：`PATCH /forum/posts/{id}/status` 隐藏/恢复/置顶，必须提供 `status` 或 `pinned`；删除仍走 `DELETE`，不能通过状态接口改成 `DELETED`。
- 评论：顶级评论 + 楼中楼回复，回复一条回复时仍归入同一顶级线程（展示两层）；评论线程按顶级评论分页，每个线程带全部回复；顶级评论删除时级联软删除其全部回复。
- 权限：`forum:post:write`、`forum:comment:write` 控制发帖/评论；`forum:moderate` 可管理全部帖子与评论；`forum:category:manage` 管理分类。Controller 的 `@PreAuthorize` 与 Service 数据归属校验叠加，读不到/已删除统一 404。
- 并发：帖子内容修改使用 `version`；评论创建/删除先 `SELECT ... FOR UPDATE` 锁定帖子行，串行化 `comment_count` 与帖子删除，避免并发丢计数。
- 检索：复用 `SearchTerms` 的中文 2 字 ngram 拆词，SQL 内使用 `MATCH(title, content) AGAINST(... IN BOOLEAN MODE)`，并先按分类/状态过滤。
- 审计：分类创建/修改/删除、帖子创建/修改/删除/隐藏恢复置顶、评论创建/删除均写审计，detail 不保存正文全文。

**测试与验证**

| 类别 | 数量 | 覆盖 |
|---|---:|---|
| 单元测试 | 50 | 阶段四 50（`SearchTerms` 抽取后原检索测试仍通过） |
| 集成测试 | 91 | 阶段四 75 + `ForumCategoryIT` 4 + `ForumPostIT` 7 + `ForumCommentIT` 5 |
| 合计 | 141 | 失败 0、错误 0、跳过 0 |

验证环境与结果（2026-10-27）：
- JDK 17.0.20.1（Temurin）、Maven 3.10.0、MySQL 8.0.46；Flyway 在空库上依次执行 V1–V4 成功。
- `mvn clean verify -Pit`：50 单元 + 91 集成，`BUILD SUCCESS`。
- 分类/软删除：分类停用后普通用户列表、详情、帖子详情均不可见；教师无 `forum:category:manage` 返回 403；分类下有帖子（含已删除帖子）时删除返回 409。
- 帖子：学生可发帖/改帖/删帖；他人修改/删除返回 403；过期 `version` 返回 409；软删除后详情 404、列表排除、分类帖子数下降；置顶后列表顺序改变。
- 检索：中文标题和正文关键词均可在唯一分类内命中；无关键词/无结果/纯标点查询返回空结果，不会退化为全量列表。
- 评论：回复顶级评论、回复回复均生成两级线程；跨帖子父评论返回 422；顶级评论删除级联回复，计数归零；删除单条回复保留兄弟回复；6 个并发评论全部成功且 `comment_count=6`。
- 审计：`FORUM_POST_CREATE`、`FORUM_POST_MODERATE` 可按 `targetId` 查询到，普通学生访问审计接口返回 403。

**未包含**
- 前端页面；当前只交付后端接口与 Swagger 文档。
- 评论编辑、点赞/收藏、@提醒、通知、附件/图片上传。
- 版主按分类授权；当前 `forum:moderate` 是全局版主权限，没有“某分类版主”表。
- 帖子全文检索的相关度调优、热帖/浏览数统计；当前只做 ngram 召回和置顶/活跃时间排序。
- 帖子/评论的敏感词过滤、举报与审核队列（当前由管理员手工隐藏/删除）。

## 阶段六：课程、排课、选课与请假（已完成）

**已交付**
- `V5__init_course.sql`：`semester`、`course`、`course_teacher`、`course_schedule`、`course_selection`、`leave_request`；含容量 `CHECK`、`UNIQUE(semester_id, code)`、选课生成列 `active_key` 唯一约束、请假时间 `CHECK`；并为 V2 预留的 `kb_space.scope_course_id` 补上课程外键。
- 学期：创建/修改/列表/详情/当前学期/状态机（`DRAFT → ACTIVE → CLOSED`，`CLOSED` 终态）；选课窗口独立于学期起止日期，只有 `ACTIVE` + 窗口内才允许选课。
- 课程：创建（创建人自动成为主讲）、整体修改（`version` 乐观锁）、状态机（`DRAFT/OPEN/CLOSED/CANCELLED`）、列表与筛选、可选课程列表、详情（含教师、排课、本人选课状态与不可选原因）。
- 授课教师：复合主键 `(course_id, teacher_id)`；`PRIMARY` 唯一、`ASSISTANT` 可多名；变更主讲需要 `course:manage_all`，主讲不能直接移除；新增教师时校验其在同一学期的其它课程时间冲突。
- 排课：星期/节次/周次/单双周/教室；三层校验（参数有序、本课程不重叠、授课教师课表不冲突）；`GET /schedules/me` 对学生返回已选课程、对教师返回任教课程，合并去重并按星期/节次排序。
- 选课：选课窗口、课程 `OPEN`、容量条件更新（`selected_count < capacity`）防超卖、生成列唯一约束防重复、时间冲突检测（星期 + 节次 + 周次 + 单双周）、退课后可重选；按学生行锁串行化“查冲突 → 写选课”。
- 请假：学生只能对已选课程提交；时间必须落在学期范围内、可关联本课程排课；`PENDING → APPROVED/REJECTED`（任课教师或管理员）、`PENDING → CANCELLED`（申请人）；全部状态变更使用 `WHERE status = 'PENDING'` 条件更新，并发审批只有一个成功。
- 课程停开（软删除）级联：批量退掉有效选课、清零 `selected_count`、撤销该课程待审批请假；停开课程对普通用户 404，仅任课教师与管理员可见。
- 权限：`course:read`、`course:manage_own`、`course:manage_all`、`selection:select`、`selection:manage_all`、`leave:submit`、`leave:review`、`leave:manage_all`；归属判断集中在 `CourseAccessService`，Service 方法直接接收 `AuthenticatedUser`，阶段七的 AI 工具已直接复用这些方法。
- 打通阶段三遗留项：`kb_space.visibility=COURSE` 启用，创建/修改需指定 `scopeCourseId` 且必须是该课程任课教师或管理员；可见范围按“任课教师 + 有效选课学生”实时计算，退课后立即失去访问权。
- 审计：学期、课程、排课、选课、请假的创建/修改/状态变更/审批均写 `audit_log`，detail 不含正文与个人信息全文。

**测试与验证**

| 类别 | 数量 | 覆盖 |
|---|---:|---|
| 单元测试 | 60 | 阶段五 50 + `ScheduleConflictTest` 10（节次/周次重叠、单双周互斥、ALL 兼容、空值处理） |
| 集成测试 | 120 | 阶段五 91 + `SemesterIT` 4 + `CourseIT` 6 + `CourseScheduleIT` 5 + `CourseSelectionIT` 6 + `LeaveRequestIT` 6 + `CourseKnowledgeIT` 2 |
| 合计 | 180 | 失败 0、错误 0、跳过 0 |

验证环境与结果（2026-10-27）：
- JDK 17.0.20.1（Temurin）、Maven 3.10.0、MySQL 8.0.46；Flyway 在空库上依次执行 V1–V5 成功（V5 的生成列、`CHECK`、外键均生效）。
- `mvn clean verify -Pit`：60 单元 + 120 集成，`BUILD SUCCESS`。
- 学期：编码重复 409、日期/窗口颠倒 400、`CLOSED` 后不能再改或重新打开 422、`current` 返回覆盖今天的 `ACTIVE` 学期。
- 课程：教师创建即主讲；他人修改 403；过期 `version` 409；未排课不能开放 422；缩容低于已选人数 422；停开后学生详情 404、列表默认排除、`status=CANCELLED` 仅管理员可查。
- 排课：同课程重叠 422、节次/周次颠倒 422、单双周同槽位可共存、教师跨课程冲突 422 并在消息中给出冲突课程。
- 选课：重复选课 409、容量满 422、时间冲突 422（含冲突课程名）、退课后可重选且计数一致；**8 个并发选课容量 3 时恰好 3 成功、`selected_count=3`**；名单仅任课教师/管理员可见。
- 请假：未选课 422、时间颠倒/超出学期 422、越权审批 403、非本人撤销 403、终态再审批 409；**教师与管理员并发审批同一申请时恰好 1 个 200、1 个 409**。
- 知识库 `COURSE`：教师建库后教师与已选学生可读、未选课学生 404；退课后立即 404 且从列表消失；非任课教师建库 403、缺 `scopeCourseId` 422、课程停开 422。

**未包含**
- 前端页面；当前只交付后端接口与 Swagger 文档。
- 选课抽签/志愿优先级、候补队列、学分上限校验、培养方案与先修课约束。
- 排课教室冲突检测（当前只校验课程自身与教师课表，未建模教室资源）。
- 请假与考勤/成绩联动、请假销假与附件上传、请假时长额度控制。
- 课程归档与历史学期只读快照；`CLOSED` 学期仍保留课程可读。

## 阶段七：AI 工具调用（已完成）

**已交付**
- `V6__init_ai_tool.sql`：`ai_tool_call`（工具名、参数 JSON 快照、参数摘要、待确认预览、状态、确认截止时间、结果摘要、错误码、延迟、`version`、会话外键与用户外键）。
- 工具框架：`AiTool` 接口 + `ToolParameter/ToolPreview/ToolResult` + `AiToolArguments`（不可信参数的形状校验）+ `AiToolRegistry`（启动时校验工具名唯一）+ 5 个 `AiToolProvider`。
- 19 个工具，全部只调用既有 Service，不复制业务规则：
  - 知识库（只读）：`searchKnowledge`、`listKnowledgeDocuments`、`getKnowledgeDocument`；
  - 课程（只读）：`getMySchedule`、`searchAvailableCourses`、`getCourseDetails`；
  - 课程（写）：`createCourse`、`updateCourse`、`updateCourseSchedule`；
  - 选课（写）：`selectCourse`、`dropCourse`；
  - 请假：`getMyLeaveRequests`（只读）、`submitLeaveRequest`、`reviewLeaveRequest`（写）；
  - 论坛：`searchForumPosts`（只读）、`createForumPost`、`updateForumPost`、`createForumComment`、`deleteForumPost`（写）。
- 两阶段执行：写操作工具调用时**不执行**，只落 `PENDING_CONFIRM` 记录并返回“目标与影响”预览与确认截止时间（`app.ai.tool.confirm-ttl`，默认 10 分钟）；确认时用条件更新认领（`PENDING_CONFIRM → EXECUTING`）后按同一份参数执行，再终结为 `SUCCEEDED/FAILED`。
- 并发与状态：并发确认只有一个请求能认领成功，其余返回 409；超过截止时间未确认标记 `EXPIRED`；已确认/已拒绝的记录不能再确认；执行失败也保留 `FAILED` 记录与错误码，不会停留在 `EXECUTING`。
- 权限两层：`ai:tool:use` + 工具声明的功能权限（满足任一），再交由业务 Service 做数据归属判断；工具目录按权限过滤，学生看不到 `createCourse`/`reviewLeaveRequest`，教师看不到 `selectCourse`。
- 审计：每次调用/确认/拒绝/过期都写 `audit_log`，`source=AI_TOOL`，detail 记录工具名、是否写操作、状态与参数摘要（不含正文与敏感数据）。
- 记录可见性：本人可查自己的调用记录，管理员可查全部并按 `userId` 过滤；越权读取返回 404。

**测试与验证**

| 类别 | 数量 | 覆盖 |
|---|---:|---|
| 单元测试 | 68 | 阶段六 60 + `AiToolArgumentsTest` 8（必填/长度/数字/区间/数组/时间格式/枚举/摘要截断） |
| 集成测试 | 129 | 阶段六 120 + `AiToolIT` 9 |
| 合计 | 197 | 失败 0、错误 0、跳过 0 |

验证环境与结果（2026-10-27）：
- JDK 17.0.20.1（Temurin）、Maven 3.10.0、MySQL 8.0.46；Flyway 在空库上依次执行 V1–V6 成功。
- `mvn clean verify -Pit`：68 单元 + 129 集成，`BUILD SUCCESS`。
- 目录：未登录 401、无角色 403；学生目录含选课/请假/论坛工具、不含建课与审批工具；教师含建课与审批、不含选课。
- 只读：`getMySchedule` 立即返回课表（`SUCCEEDED`、无确认截止时间），记录落库，审计 `AI_TOOL_INVOKE` 且 `source=AI_TOOL`。
- 写操作：`selectCourse` 返回 `PENDING_CONFIRM` 与含课程编码的预览，此时 `course_selection` 与 `selected_count` 均未变化；确认后为 `SUCCEEDED` 且名额 +1；再次确认返回 409。
- 拒绝/过期：拒绝后状态 `REJECTED` 且无选课记录；把 `confirm_expires_at` 改到过去后确认返回 409 `AI_TOOL_CONFIRM_EXPIRED` 并标记 `EXPIRED`。
- 并发确认：4 个并发确认恰好 1 个 200、3 个 409，最终只有 1 条选课记录。
- 业务规则仍生效：非任课教师审批他人课程请假在预览阶段即 404；重复选课在确认阶段被业务拒绝，记录标记 `FAILED` 且 `error_code=SELECTION_DUPLICATED`。
- 参数与越权：未知工具 404、缺参/类型错误 400 `AI_TOOL_ARGUMENT_INVALID`、学生调 `createCourse` 403、教师调 `getMyLeaveRequests` 403。

**未包含**
- 模型侧 function calling 编排：后端提供工具目录与调用/确认 API，由编排层（前端 Agent 或未来的模型适配器）决定调用哪个工具；后端不解析模型自由文本为工具调用，避免提示注入。
- 对话内自动工具循环（一次问答内多次工具调用与结果回填）、工具调用计费与配额。
- 工具级限流、工具执行超时与自动补偿；`EXECUTING` 若因进程崩溃长期停留，需要人工按 `executed_at` 判断（未做定时清理）。
- 前端确认页；当前只交付接口与 Swagger。

## 阶段八：系统测试与部署文档（已完成）

**已交付**
- 跨模块全链路测试 `SystemFlowIT`（2 个用例）：
  - 知识库检索 → AI 问答（本地假模型，校验引用与 Token）→ 论坛发帖/评论/关键词检索；
  - 建学期/建课/排课/开放 → 选课 → 请假审批 → AI 工具读课表 → AI 工具退课（待确认 → 确认）→ 审计追溯。
- 混合并发测试 `ConcurrencyIT`（3 个用例）：选课与停开课程同时发生、同一学生并发重复选课、审批与撤销请假同时发生。
- 故障与错误映射测试 `FailureModeIT`（2 个用例）：失败选课不占名额；可预期业务失败一律 4xx（400/404/409/422），不冒泡成 500。
- 权限矩阵回归 `AccessMatrixCourseToolIT`（1 个用例）：学期写、建课、排课、名单、选课、工具目录与工具调用的角色矩阵，并验证管理员/教师的正向路径。
- 部署文档 `docs/06-deployment.md`：部署形态与前置条件、构建产物、数据库初始化、**完整环境变量清单**、Flyway 启动与升级流程、Nginx/HTTPS 与 forward headers、日志与审计留存、备份恢复、健康检查、冒烟步骤、故障排查表、上线安全清单。
- 运维脚本：
  - `scripts/init-db.sql`：建库 + 建应用账号 + 最小权限 + `ngram_token_size` 校验；
  - `scripts/backup.sh`：`mysqldump --single-transaction` + gzip，自带完整性与 `flyway_schema_history` 校验；
  - `scripts/smoke.sh`：健康检查 → 登录 → 工具目录 → 只读工具 → 写工具确认闸门（拒绝且不产生数据），失败退出码非 0，结束自动清理临时分类。

**测试与验证**

| 类别 | 数量 | 覆盖 |
|---|---:|---|
| 单元测试 | 68 | 阶段七 68（阶段八未新增单元测试） |
| 集成测试 | 137 | 阶段七 129 + `SystemFlowIT` 2 + `ConcurrencyIT` 3 + `FailureModeIT` 2 + `AccessMatrixCourseToolIT` 1 |
| 合计 | 205 | 失败 0、错误 0、跳过 0 |

验证环境与结果（2026-10-27）：
- JDK 17.0.20.1（Temurin）、Maven 3.10.0、MySQL 8.0.46。
- `mvn clean verify -Pit`：68 单元 + 137 集成，`BUILD SUCCESS`。
- 混合并发：6 个学生选课（容量 3）与教师停开课程同时进行，最终 `selected_count=0`、无任何 `SELECTED` 记录、成功选课数 ≤ 3；同一学生 6 个并发选课恰好 1 个 200、5 个 409，只留 1 条有效记录；审批与撤销并发时恰好一个 200、一个 409，终态二选一。
- 故障映射：课程编码重复 409、未排课开放 422、请假超学期 422、跨帖父评论 422、未知工具 404、工具缺参 400、资源不存在 404，全部为 4xx。
- **打包冒烟**：全新库 `campus_ai_smoke` 启动 jar（JDK 17），Flyway 依次应用 V1–V6（`now at version v6`），初始管理员 `smoke_admin` 创建成功；`scripts/smoke.sh` 全流程通过（健康检查、登录、工具目录、只读工具 `SUCCEEDED`、写工具 `PENDING_CONFIRM → REJECTED` 且未产生帖子）；日志中检索不到管理员密码与 JWT 密钥；冒烟后 `forum_category`/`forum_post` 均为 0 条。
- `scripts/backup.sh` 对冒烟库执行成功，备份文件 9.5K、含 29 条 `CREATE TABLE`、`gzip -t` 通过。

**未包含**
- 压力/容量测试（JMeter/Gatling 长稳压测）、混沌工程、跨实例多节点测试。
- 自动化 CI 流水线（当前为本地 Maven 命令）；容器镜像与编排文件（Dockerfile / K8s manifest）。
- 监控告警接入（Prometheus/Grafana 指标导出、告警规则）。
- 前端部署（当前只交付后端接口与 Swagger）。
- Qdrant/Embedding 向量检索仍为后续增量，接口已预留。
