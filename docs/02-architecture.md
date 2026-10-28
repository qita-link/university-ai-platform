# 02 架构、模块与技术决策

## 1. 架构形态

模块化单体（Modular Monolith）：单一 Spring Boot 应用、单一 MySQL 库，按业务模块划分 Java 包。模块之间只通过对方的 Service 接口交互，不直接访问对方的 Mapper 或表。后续如需拆分服务，以模块为边界即可。

```
HTTP 请求 ──> Controller（DTO 入参校验、@PreAuthorize 功能权限）
                 │
AI 工具调用 ──> ToolAdapter（参数整理、确认流程）
                 │
                 ▼
              Service（数据归属校验、业务规则、事务、审计）  <── 唯一的业务逻辑实现
                 │
                 ▼
              Mapper（MyBatis-Plus） ──> MySQL
```

AI 工具与 REST 接口复用同一个 Service 方法，权限与数据归属都在 Service 层基于 `AuthenticatedUser` 判断，AI 无法绕过。

## 2. 依赖版本与兼容性

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 17 | |
| Spring Boot | 3.4.13 | 3.4.x 线最新补丁版 |
| Spring Framework / Security | 6.2.15 / 6.4.13 | 由 Boot 管理 |
| Spring AI | 1.0.3（BOM） | 见下方说明 |
| MyBatis-Plus | 3.5.14（BOM） | `mybatis-plus-spring-boot3-starter` + `mybatis-plus-jsqlparser` |
| springdoc-openapi | 2.8.8 | |
| Flyway | 10.20.1 | 由 Boot 管理，需配合 `flyway-mysql` |
| MySQL Connector/J | 9.5.0 | 由 Boot 管理，兼容 MySQL 8.0/8.4 |
| MySQL Server | 8.0.x 或 8.4 LTS | 阶段一在 8.4.10、阶段二在 8.0.46 上通过自动化测试 |

兼容性检查结论（2026-10-26 根据 Maven Central 的 POM 核对）：

1. **Spring AI 1.0.x 与 Boot 3.4.x**：Spring AI 1.0.0 基于 Boot 3.4.5 构建，1.0.3 起构建基线升为 Boot 3.5.x，1.1.x 和 2.x 更高（2.x 面向 Boot 4）。本项目在 Boot 3.4.13 上编译、启动正常，并由 `SpringAiCompatibilityIT` 验证 OpenAI ChatModel 自动配置可以正常装配；阶段四用本地假 ChatModel 验证了问答/引用/Token 流程，真实模型调用仍需配置 API Key。**升级 Spring AI 前必须同时评估 Boot 版本**，不要单独升级到 1.1.x/2.x。
2. **MyBatis-Plus**：3.5.15 及以后的版本基于 Boot 3.5.x 构建，3.5.14 基于 Boot 3.4.1 构建，因此选用 3.5.14。从 3.5.9 起分页插件拆分为独立的 `mybatis-plus-jsqlparser` 模块，必须单独引入。
3. **springdoc**：2.8.9 起基于 Boot 3.5，2.8.8 基于 Boot 3.4.5，因此选用 2.8.8。
4. **Flyway 10.20 与 MySQL 8.4**：启动时会输出“Flyway upgrade recommended: MySQL 8.4 is newer…”警告，不影响功能，迁移在 8.4 上测试通过。如果生产环境使用 8.0，则不会出现该警告。
5. **Spring AI 重试**：Spring AI 默认重试 10 次，已通过 `spring.ai.retry.max-attempts=2` 收紧，避免放大延迟和费用。

## 3. 模块划分（包 `com.campus.platform`）

| 包 | 职责 | 当前状态 |
|---|---|---|
| `common` | 统一响应 `ApiResponse`、分页 `PageQuery/PageResult`、错误码与 `BusinessException`、全局异常处理、traceId、日志脱敏、实体基类与字段自动填充 | 阶段一完成 |
| `config` | 时钟、MyBatis-Plus 插件（分页上限、乐观锁、防全表更新）、OpenAPI、通用配置 | 阶段一完成 |
| `security` | JWT 签发与校验、`AuthenticatedUser`、401/403 JSON 处理、CORS、密码编码 | 阶段一完成基础设施，阶段二完善 |
| `auth` | 登录、退出、修改本人密码、登录限流 | 阶段二完成 |
| `user` | 用户、角色、权限、组织单元管理、`DataScopeService`、初始管理员引导 | 阶段二完成 |
| `audit` | 审计写入（独立事务、脱敏）与管理员查询 | 阶段一完成 |
| `system` | 公开系统信息 | 阶段一完成 |
| `knowledge` | 知识库分类/空间/成员、Markdown 上传/解析/切分/版本、在线编辑、本地语料导入 | 阶段三完成 |
| `retrieval` | `KnowledgeRetrievalService` 统一检索接口；MySQL ngram 关键词实现，Qdrant/混合检索待接入 | 阶段四关键词完成 |
| `ai` | 对话、提示词构建、上下文预算、引用、Token 统计；工具注册、工具调用与两阶段确认 | 阶段四问答完成，阶段七工具完成 |
| `forum` | 论坛分类、帖子、两级评论、关键词检索、版主隐藏/恢复/置顶 | 阶段五完成 |
| `course` | 学期、课程、授课教师、排课与课表、选课与退课、请假申请与审批；`CourseMembershipService` 供知识库 `COURSE` 可见范围复用 | 阶段六完成 |

每个模块内部按 `controller / service / mapper / entity / dto` 分层，模块较小时可以省略部分子包。

## 4. 安全模型

**认证**：无状态 JWT（HS256，`Authorization: Bearer`）。令牌只携带 `sub`（用户 ID）、`username`、`ver`（token_version）和 `typ`。每个请求都会从数据库重新加载用户的状态、角色和权限，因此：
- 停用账号以及调整角色、权限后，立即生效；
- 修改密码或强制下线时让 `sys_user.token_version` 自增，旧令牌立即失效。

**授权分三层**：
1. URL 层：除白名单（登录、系统信息、健康检查、Swagger）外全部要求登录；
2. 功能层：Controller 方法使用 `@PreAuthorize("hasAuthority('权限点')")`，权限点见数据库设计文档；
3. 数据层：Service 根据 `AuthenticatedUser` 校验数据归属，例如教师只能修改自己任教的课程，学生只能看到自己的请假记录。**第三层不可省略**，因为 AI 工具调用同样经过它。

**其他措施**：
- 密码：`DelegatingPasswordEncoder`（bcrypt，带 `{bcrypt}` 前缀，便于将来升级算法）。密码策略默认至少 8 位、至少包含小写字母/大写字母/数字/符号中的 3 类，不能包含用户名（忽略大小写），拒绝控制字符和超过 bcrypt 安全上限（72 字节）的密码。
- 异常：数据库异常、堆栈和 SQL 不返回给客户端，响应只含错误码、提示语和 traceId。
- 日志：Logback 使用 `%maskedMsg` 自动脱敏 password、token、apiKey、Bearer、JWT、`sk-` 等；不输出 SQL 参数和请求体。
- 审计：写入使用独立事务（`REQUIRES_NEW`），业务回滚时仍保留失败或拒绝记录；detail 自动脱敏并截断。
- 秘密信息：JWT 密钥、数据库密码和模型 API Key 只来自环境变量或被 git 忽略的 `config/application-local.yml`。未配置 JWT 密钥（或不足 32 字节）时应用拒绝启动。
- 初始管理员：只有在不存在可用管理员时才根据环境变量创建；不会生成默认密码，也不会把已存在的普通账号自动提升为管理员。
- HTTP 头：`X-Frame-Options: DENY`、`X-Content-Type-Options: nosniff`、`Referrer-Policy: no-referrer`；CORS 默认关闭，按白名单开放。
- traceId：接受上游传入的 `X-Request-Id`，但只接受 `[A-Za-z0-9_-]{8,64}`，防止日志注入。

### 4.1 登录、锁定与限流

- **防枚举**：用户不存在时对一个启动时生成的随机 bcrypt 哈希执行一次 `matches`，使耗时与“用户存在但密码错误”接近；两者返回同一错误码和提示。账号停用/锁定只在密码正确后才提示，避免通过错误信息探测账号状态。
- **临时锁定**：连续失败达到阈值后设置 `locked_until`。锁定期间不校验密码，直接返回 `AUTH_ACCOUNT_LOCKED`，避免用“锁定/密码错误”两种响应继续猜密码；锁定到期后下一次失败从 1 重新计数；成功登录清零。临时锁定不吊销已签发令牌，防止攻击者通过故意输错密码把在线用户踢下线。
- **并发计数**：bcrypt 校验在事务外完成；失败计数在事务内对用户行 `FOR UPDATE` 后读取并更新，锁内再次检查锁定状态。因此并发失败不会丢计数，并发正确密码也不能越过刚生效的锁定。
- **限流**：进程内令牌桶，不引入 Redis。登录同时检查 `IP` 与 `IP + 用户名`（用户名转小写，避免大小写绕过）；修改密码按用户 ID 限流。超限返回 429 与 `Retry-After`，限流请求不写审计，避免攻击流量刷爆审计表。多实例部署时每个实例独立计数，这是当前阶段的明确限制。
- **令牌吊销**：退出、改密、管理员重置密码、停用/锁定账号时 `token_version` 自增；JWT 中的 `ver` 与数据库不一致即拒绝。角色变更不需要吊销令牌，因为每个请求都会重新加载权限。
- **修改密码**：先验证旧密码，再执行密码策略并拒绝与原密码相同；更新时以旧哈希为条件，避免与管理员并发重置互相覆盖；成功后返回新令牌，当前客户端无需重新登录。

### 4.2 用户与组织保护规则

- **不能对自己**：停用/锁定、修改角色、重置密码均拒绝，避免误操作把自己锁在系统外；本人改密走 `/auth/password`。
- **至少一个可用管理员**：停用/锁定管理员或移除 ADMIN 角色前，先按 ID 顺序锁定全部 ACTIVE 管理员用户行，再重新计数；并发的互相停用/降级会串行化，只有一方成功。
- **不能越权管理**：操作人必须拥有目标用户的全部权限点，才能修改其资料、状态、密码或角色；分配的角色所含权限点也必须是操作人权限的子集。这样仅有 `user:manage` 的账号不能接管管理员，也不能通过给别人授权间接提权。
- **组织层级**：`OrgType.rank` 严格递增，下级类型必须高于上级；可以跨级（学院下直接建专业），但最多 4 层，从结构上消除环。移动上级时仍显式检查“新上级不能是自己或自己的下级”，并检查类型变化后现有下级是否仍合法。
- **组织启停**：停用前必须停用全部直接下级；启用时上级必须启用。因此“启用的组织，其所有上级都启用”始终成立。组织不物理删除，用户与后续业务数据通过外键引用它。
- **可见性**：所有登录用户可查看启用的组织；`includeDisabled=true` 和已停用组织详情需要 `org:manage`。停用组织对普通用户表现为 404，不泄露其存在。

### 4.3 数据权限

`DataScopeService` 只回答组织层面的范围问题，不判断作者、课程教师、知识库成员等业务归属；这些由各业务服务在此基础上叠加判断。

| 方法 | 语义 |
|---|---|
| `orgAndDescendants(orgId)` | 组织自身 + 全部下级（不区分状态）；组织不存在返回空集合 |
| `orgAndAncestors(orgId)` | 组织自身 + 全部上级（从自身到根） |
| `manageableScope(user, globalPermission)` | 持有指定全局权限点时返回 `DataScope.all()`，否则返回本人组织及全部下级；无组织返回 `none()` |
| `membershipOrgIds(user)` | 本人组织及全部上级；用于判断“对组织 X 及其下级开放”的资源是否对用户可见 |
| `isMemberOf(user, orgId)` | `orgId` 是否在 `membershipOrgIds` 中 |
| `requireManageable(user, orgId, globalPermission)` | 不在可管理范围时抛 `FORBIDDEN` |

`DataScope` 明确区分 `all()`、`none()` 和有限组织集合；`applyTo` 把范围追加为 SQL 条件：`all` 不加条件，`none` 追加恒假条件，有限集合追加 `IN`。业务查询应使用 SQL 条件而不是先查全量再在内存过滤。组织状态不参与范围计算：组织停用不会让历史数据失去归属，是否因停用而隐藏由业务模块决定。

### 4.4 并发与事务

- **用户管理**使用 `READ_COMMITTED`。InnoDB 默认的 `REPEATABLE READ` 会在第一条普通 SELECT 时固定快照，加锁后的角色/权限读取可能看到旧数据；`READ_COMMITTED` 下每条语句读取最新已提交数据。
- **统一加锁顺序**：涉及管理员保护的操作先 `lockActiveAdminIds()`（按 ID 排序），再 `selectByIdForUpdate` 目标用户；避免两个管理员互相操作时死锁。
- **登录计数**只更新 `failed_login_count/locked_until/last_login_at`，并显式保持 `updated_at` 不变、不递增 `version`；管理员编辑用户资料使用 `WHERE version = ?` 并递增 `version`，两者互不干扰。
- **组织变更**先 `SELECT ... FOR UPDATE` 锁定整棵树；组织数量在千级以内且只有管理员修改，整表锁的代价可接受，换来结构校验的简单与正确。
- **禁止使用 `updateById(entity)` 更新用户**：实体是先读后写的快照，会把旧的 `token_version`、失败计数写回，可能让已吊销令牌重新生效。所有用户更新使用只修改目标列的 SQL。

### 4.5 知识库权限与文档处理

- **功能权限与数据权限叠加**：`kb:space:read` 是读取入口，`kb:document:write` 是写入入口；Controller 的 `@PreAuthorize` 只做第一层，`KnowledgeAccessService` 再按空间 owner/成员角色/可见范围判断。读不到的空间统一 404。
- **可见范围**：`PUBLIC` 所有登录用户可读；`ORG` 用户所属组织在 `scope_org_unit_id` 或其下级时可读；`PRIVATE` 仅 owner 和成员；`COURSE` 由 `CourseMembershipService` 判定“该课程的任课教师或有效选课学生”，退课后立即失去访问权（阶段六接入）。`kb:space:manage_all`（ADMIN）不受限；PUBLIC 空间只允许具备该权限的账号创建。
- **成员角色**：MANAGER 可管理成员和文档，EDITOR 可写文档，VIEWER 只读。owner 隐式拥有 MANAGER 权限，并写入成员表；不能被移除或改角色。成员身份不等于功能权限，例如 STUDENT 即使是 EDITOR，仍因缺少 `kb:document:write` 不能写。
- **文档生命周期**：每次上传/在线编辑生成不可变 `kb_document_version`；旧版本块标记 `STALE`，新版本块 `ACTIVE`；删除为软删除并同步标记块；重处理只重建当前版本的块，不新增版本。
- **批量上传**：逐文件独立事务，单个文件失败不影响其他文件；失败也保留一条 `process_status=FAILED` 的文档记录，便于查看和重试；批次状态为 `COMPLETED/PARTIAL/FAILED`。
- **分块**：`MarkdownChunker` 按标题层级分节，再按 `app.knowledge.max-chunk-chars`（默认 800）和 `chunk-overlap-chars`（默认 120）切块；保留 `heading_path`，清理 Markdown 标记后写入 `kb_chunk.content`。
- **索引**：`kb_chunk` 上的 ngram FULLTEXT 索引由阶段四的 `MysqlKeywordRetrievalService` 使用；标题和正文分两个索引并做加权。
- **文件安全**：只接受 `.md`，限制单文件和批量数量，文件名只保留 basename；原文存数据库 MEDIUMTEXT，不使用本地文件路径，避免路径穿越和文件残留。
- **本地语料导入**：仅由 `app.knowledge.import.enabled=true` 的启动导入器触发，默认关闭；按 source_path + content_hash 幂等，内容变化生成新版本；导入器不作为生产 HTTP 上传接口。

### 4.6 检索与 AI 问答

**检索流程**：
1. 用 `KnowledgeSpaceService.accessibleSpaceIds` 先计算当前用户可读空间；如果结果为空，直接返回无结果，不查 chunk。
2. `MysqlKeywordRetrievalService` 把自然语言拆成中文 2 字 ngram 和英文/数字词，去掉问句停用词并清理 BOOLEAN MODE 操作符；长句直接交给 ngram 会匹配不到，因此这一步是必需的。
3. SQL 使用两个 FULLTEXT 索引：`document_title` 分数 ×4，`heading_path + content` 分数 ×1；按总分降序。
4. 应用层按 `content` 的 SHA-256 去重、过滤分数、截断 snippet；返回 chunk/document/version/space ID、标题、章节、分数和 `insufficientEvidence`。
5. `SearchStrategy` 预留 VECTOR/HYBRID；替换成 Qdrant 时保持 `KnowledgeRetrievalService` 接口和上层调用不变。

**AI 问答流程**：
1. 校验 `ai:chat`；创建/复用本人会话，保存 USER 消息。
2. 检索候选，按 `app.ai.max-context-tokens` 选入上下文，最多 `top-k` 个块；每块保留标题和 `heading_path`。
3. 组装系统消息（只依据资料、拒绝资料中的指令、引用编号）和历史消息；调用 Spring AI `ChatModel`。
4. 保存 ASSISTANT 消息、检索记录、引用快照（文档/版本/chunk/分数/snippet）、Token 用量和延迟；`token_source` 区分 REAL/ESTIMATED。
5. 检索为空时**不调用模型**，保存 `NO_EVIDENCE` 消息并返回明确的“没有找到足够依据”。
6. 未配置 ChatModel 时保存 `FAILED` 消息、返回 503，不生成回答；模型调用失败同样保留失败记录。
7. 会话列表/详情/删除仅限本人；引用从 chunk 表补全标题和章节，历史引用不依赖当前 chunk 是否仍为 ACTIVE。

**AI 相关安全原则**（阶段四实施检索/问答，阶段七实施工具调用，见 4.9）：
- 检索之前先计算用户可访问的 `space_id` 集合，并把它作为 SQL 条件；不会先检索全部内容再交给模型过滤。
- 检索到的文档片段放在提示词中明确的“资料”分隔区内，并声明其中的内容不是指令；模型输出也视为不可信输入。
- 工具参数一律由服务端重新校验；写操作两阶段执行：先生成待确认操作并展示给用户，用户确认后由后端执行。确认凭据与用户、会话绑定，并且有过期时间。
- 不向模型提供任何 SQL 能力或数据库凭据。

### 4.7 论坛

- **可见性**：登录用户即可读取分类和帖子，不需要额外的 `forum:read` 权限点。帖子状态为 `PUBLISHED/HIDDEN/DELETED`：`HIDDEN` 仅作者本人和版主可见，`DELETED` 对所有人 404；分类状态为 `ACTIVE/DISABLED`，停用分类后其下帖子对普通用户（含作者）不可见，也不能继续发帖。
- **内容权限**：`forum:post:write` 允许发帖、修改/删除自己的帖子；`forum:comment:write` 允许评论、删除自己的评论；`forum:moderate` 可编辑/删除任意帖子、删除任意评论、隐藏/恢复/置顶；`forum:category:manage` 管理分类。Service 层再次做数据归属判断，阶段七 AI 工具复用同一入口。
- **两级评论**：顶级评论 `parent_id/root_id` 均为空；回复的 `root_id` 指向顶级评论，`parent_id` 指向被回复的评论。回复一条回复时仍写入同一 `root_id`，展示时统一挂在顶级评论下，因此线程深度固定为两层，不需要递归查询。删除顶级评论级联软删除其全部回复；删除单条回复只影响该回复，其“回复的回复”保留。
- **软删除**：帖子删除写 `status=DELETED`、`deleted_at/deleted_by`；评论同样软删除。读取接口先排除已删除数据；分类删除前要求没有任何帖子（包括已删除帖子，外键和历史追溯不允许物理删除帖子）。
- **并发与计数**：帖子内容修改使用 `version` 乐观锁；评论创建/删除在事务内先 `SELECT ... FROM forum_post WHERE id = ? FOR UPDATE` 锁定帖子行，再写评论并增减 `comment_count`，避免并发丢计数、给已删除帖子追加评论或与帖子删除竞争。评论计数更新不递增帖子 `version`，作者编辑内容与评论活跃互不阻塞。
- **检索**：帖子使用 `FULLTEXT(title, content) WITH PARSER ngram`；中文查询复用 `common.util.SearchTerms` 拆 2 字 ngram，SQL 内用 `MATCH ... AGAINST(... IN BOOLEAN MODE)`，并按分类/状态过滤。列表排序为置顶优先、其次最后活跃时间倒序；关键词检索时在同等置顶下按相关度优先。
- **审计**：分类/帖子/评论的创建、修改、删除、隐藏/恢复/置顶写入 `audit_log`，`target_type` 为 `FORUM_CATEGORY/FORUM_POST/FORUM_COMMENT`，detail 只记录标题、ID、状态变化和原因，不写正文全文。

### 4.8 课程、选课与请假

- **归属判断集中在 `CourseAccessService`**：`course:manage_all`（管理员）管理全部课程；`course:manage_own`（教师）只能管理自己是**主讲**的课程；任课教师（主讲或助教）可以查看名单、审批请假，但不能改课程资料与排课。Controller 的 `@PreAuthorize` 只做功能权限第一层，Service 内部再按 `AuthenticatedUser` 判断数据归属，阶段七的 AI 工具复用同一入口。
- **学期与选课窗口**：学期状态 `DRAFT/ACTIVE/CLOSED`，`CLOSED` 为终态。选课窗口 `selection_start_at/selection_end_at` 与学期起止日期相互独立（选课通常发生在开学前），只有“学期 ACTIVE 且当前时间在窗口内”才允许选课与退课；`CLOSED` 学期不能再建课、改课、排课。
- **课程状态机**：`DRAFT → OPEN → CLOSED`，任意非终态 → `CANCELLED`；`OPEN` 前必须已排课，`CLOSED` 可以重新开放，`CANCELLED` 为终态。停开时在同一事务内批量退掉有效选课、清零 `selected_count`、撤销该课程待审批请假。状态更新使用 `WHERE status = 旧状态` 的条件更新，并发变更只有一个成功。
- **排课三层校验**：① 参数自身有序（开始节次 ≤ 结束节次、开始周 ≤ 结束周）；② 与本课程已有排课不重叠；③ 与本课程全部授课教师在同一学期的其它课程不重叠（教师不能分身）。冲突判定由纯函数 `ScheduleConflict` 完成（星期相同 + 节次区间相交 + 周次区间相交 + 单双周不互斥），便于单元测试。
- **选课并发控制（四层）**：
  1. 事务开始先 `SELECT id FROM sys_user WHERE id = ? FOR UPDATE` 锁定学生行，把同一学生的“查冲突 → 写选课”串行化，避免并发选两门互相冲突的课程时双方都通过检查；
  2. 随后 `SELECT * FROM course WHERE id = ? FOR UPDATE` 锁定课程行，与停开课程、容量变更串行化，保证停开后不会再有新选课落库；
  3. `UPDATE course SET selected_count = selected_count + 1 WHERE id = ? AND status = 'OPEN' AND selected_count < capacity`，由数据库条件更新保证不超卖；
  4. `UNIQUE(course_id, student_id, active_key)` 兜底防重复，`active_key` 是生成列（`SELECTED` 为 1，其余为 NULL），因此退课后可以重选。
- **退课**：同样先锁学生行，再用 `WHERE status = 'SELECTED'` 条件更新置为 `DROPPED`，成功后 `selected_count` 减一（`GREATEST(..., 0)` 防负数）。退课与选课共用同一窗口规则。
- **请假状态机**：`PENDING → APPROVED/REJECTED`（任课教师或管理员）、`PENDING → CANCELLED`（申请人本人）。所有变更使用 `WHERE status = 'PENDING'` 条件更新，并发审批只有一个请求写入成功，其余返回 409；审批人不能是申请人本人，教师只能审批自己任教（主讲或助教）的课程。
- **请假数据校验**：学生必须已选该课程；起止时间有序且落在学期日期范围内；可选的 `schedule_id` 必须属于该课程（排课被删除时外键置空，不连带删除请假）。
- **知识库联动**：`kb_space.visibility=COURSE` 要求指定 `scopeCourseId`，创建/修改者必须是该课程的任课教师或管理员；读取时按 `CourseMembershipService` 实时计算，退课立即失效。知识库模块只依赖课程模块的 Service，不反向依赖。

### 4.9 AI 工具调用

- **工具是“Service 的薄封装”**：每个工具只做参数整形与调用既有 Service，不实现业务规则、不自行判断数据归属。因此工具天然继承阶段二至六的全部权限与并发保证，不存在“AI 专用后门”。
- **权限两层**：先校验 `ai:tool:use` 与工具声明的功能权限（满足任一），再由业务 Service 按 `AuthenticatedUser` 判断数据归属。工具目录按权限过滤，模型与前端只会看到当前用户真正可用的工具。
- **两阶段执行**：只读工具调用即执行；写操作工具调用时**不执行**，只落 `PENDING_CONFIRM` 记录，返回“目标与影响”预览（含课程名/帖子标题/请假时间等）与确认截止时间。用户确认后才按**同一份参数**执行，保证预览与实际一致。
- **并发认领**：确认使用 `UPDATE ... SET status='EXECUTING' WHERE id=? AND status='PENDING_CONFIRM' AND confirm_expires_at > ?` 认领，只有一个请求能成功；其余返回 409。执行结束再终结为 `SUCCEEDED/FAILED`，失败也保留错误码，不会停留在 `EXECUTING`。
- **过期与拒绝**：超过 `app.ai.tool.confirm-ttl`（默认 10 分钟）未确认，确认时标记 `EXPIRED` 并返回 409；拒绝直接把记录置为 `REJECTED`，不触发任何业务动作。
- **参数不可信**：参数来自模型或调用方，`AiToolArguments` 统一做必填、类型、长度、区间、枚举、时间格式校验，失败返回 400 `AI_TOOL_ARGUMENT_INVALID` 并指出参数名；只做形状校验，业务校验仍在 Service。
- **审计**：调用、确认、拒绝、过期都写 `audit_log`，`source=AI_TOOL`，`target_type=AI_TOOL_CALL`，detail 记录工具名、是否写操作、状态与参数摘要；业务动作本身仍由各模块写自己的审计。
- **边界**：后端提供工具目录与调用/确认 API，由编排层（前端 Agent 或未来的模型适配器）决定调用哪个工具；**后端不解析模型自由文本为工具调用**，避免提示注入把普通回答变成越权操作。

## 5. 关键技术决策
| # | 决策 | 理由 |
|---|---|---|
| D1 | Markdown 原文存入数据库（`kb_document_version.content`，MEDIUMTEXT），不落本地磁盘 | 单文件不超过 2MB，数据库存储天然具备事务性和版本化；从根本上避免路径穿越和文件残留，备份时只需备份数据库。原始文件名只作为经过清洗的元数据保存 |
| D2 | 关键词检索第一版采用 MySQL `FULLTEXT ... WITH PARSER ngram`（BOOLEAN MODE），并由应用层提取关键词、标题加权、短语匹配、去重和阈值过滤 | 无需新增组件即可支持中文。ngram 在数据库端保证中文召回，应用层负责排序质量 |
| D3 | 中文关键词提取阶段四采用“2 字 ngram + 问句停用词”，暂不引入 HanLP；后续如召回质量不足再评估 | 不新增依赖，先验证 MySQL ngram 的实际效果 |
| D4 | 检索通过 `KnowledgeRetrievalService` 接口抽象，当前实现 `MysqlKeywordRetrievalService`；后续可增加 `QdrantVectorRetriever` 和 `HybridRetriever`（RRF 融合） | 更换检索方案时业务层不需要改动 |
| D5 | 大模型使用 Spring AI 的 OpenAI 兼容协议，通过 `AI_BASE_URL`/`AI_CHAT_MODEL` 切换 DeepSeek、通义、Ollama 等。默认 `AI_CHAT_PROVIDER=none`，不调用任何外部模型 | 未配置模型时应用也能启动；AI 接口返回明确的“未启用”错误，不伪造回答 |
| D6 | Markdown 解析阶段三评估 `commonmark-java`；实际只需要标题、frontmatter 和正文，最终自行实现轻量解析器（`MarkdownParser`/`MarkdownChunker`），未引入新依赖 | 保持依赖最小；后续需要完整 CommonMark 渲染时再评估 |
| D7 | 选课并发：条件更新 `selected_count = selected_count + 1 WHERE selected_count < capacity`、活跃选课唯一约束，再加上按学生加行锁（`SELECT ... FOR UPDATE`）来串行化冲突检查 | 三者分别防止超卖、重复选课和并发时间冲突 |
| D8 | 状态字段使用 VARCHAR 保存枚举名，主键使用 BIGINT 自增，时间使用 `DATETIME(3)`（业务时区 Asia/Shanghai，会话时区由 `connection-init-sql` 固定为 +08:00） | 可读、易扩展；时间口径统一 |
| D9 | 数据库迁移使用 Flyway，**每个阶段追加新的版本脚本**（V2 知识库、V3 AI、V4 论坛、V5 课程……），不修改已发布的脚本 | 保证新环境可以重复初始化，已有环境可以增量升级 |
| D10 | 第一阶段不引入 Redis、MQ 或注册中心；文档处理采用同步处理加可重试的失败状态，必要时再改为进程内异步线程池 | 遵循“非必要不引入” |
| D11 | 登录失败锁定与限流都放在应用进程内：失败计数落 MySQL，令牌桶在内存；不引入 Redis | 单实例部署足够，避免为阶段二增加运维组件；多实例的计数独立作为已知限制记录 |
| D12 | 组织层级用 `OrgType.rank` 约束，变更时整棵树加锁；组织不物理删除 | 从结构上消除环，校验简单；历史数据与后续业务外键不被破坏 |
| D13 | `DataScopeService` 返回显式的 `all/none/orgIds`，并提供 `applyTo` 生成 SQL 条件 | 避免“空集合被误当作无限制”或“先查全量再内存过滤”，供多个业务模块复用 |
| D14 | 管理员保护使用“锁定全部可用管理员行 + 重新计数”，而不是应用层缓存计数 | 并发互相停用/降级时由数据库串行化，保证至少保留一个可用管理员 |
| D15 | 知识库原文与版本存数据库（MEDIUMTEXT），chunk 存 `kb_chunk` 并用 ngram FULLTEXT；不落本地文件 | 事务性与版本一致，避免路径穿越/文件残留；阶段四可直接用 MySQL 关键词检索 |
| D16 | 文档版本不可变；旧版本块置 `STALE`，只检索当前版本 `ACTIVE` 块；批量上传逐文件独立事务 | 编辑/删除后不会命中旧内容；单个文件失败可追踪、可重试 |
| D17 | 本地语料导入是显式配置的启动任务，不是 HTTP 文件扫描接口；按 source_path + content_hash 幂等 | 便于把既有 Markdown 站灌入开发/演示环境，同时不扩大生产攻击面 |
| D18 | 中文查询先拆 2 字 ngram，再用 BOOLEAN MODE；标题单独 FULLTEXT 索引并权重 ×4 | ngram 对整句查询召回差；标题索引能提高主题文档排序 |
| D19 | 检索不足时不调用模型，直接返回 `NO_EVIDENCE`；无模型时返回 503 并保留失败消息 | 宁可明确说没有依据，也不让模型编造；失败可追溯 |
| D20 | 对话、检索记录、引用快照、Token 用量全部落库；引用保存 chunk/文档/版本 ID | 支持追溯、审计和阶段七工具调用复用 |
| D21 | 论坛评论固定两级：顶级评论 `parent_id/root_id` 为空，回复的 `root_id` 指向顶级评论、`parent_id` 指向被回复评论；回复回复仍归入同一 root | 避免无限层级树和递归查询；线程分页按顶级评论进行，回复随时间平铺展示 |
| D22 | 评论创建/删除先锁定帖子行，再维护 `comment_count`；帖子内容修改使用 `version`，评论计数不递增 `version` | 并发评论不丢计数、不与帖子删除竞争；作者编辑内容与评论活跃互不阻塞 |
| D23 | 分类停用后其下帖子对普通用户不可见；软删除帖子仍占用分类，分类删除前要求没有任何帖子 | 分类停用能整体收起板块；软删除保留追溯，外键与历史数据不被破坏 |
| D24 | 课程停开用 `status=CANCELLED`（软删除）表达，不物理删除；停开级联退课、清零计数、撤销待审请假 | 选课与请假需要保留追溯；物理删除会破坏外键与历史记录 |
| D25 | 选课唯一约束用生成列 `active_key`（`SELECTED`→1，否则 NULL）+ `UNIQUE(course_id, student_id, active_key)`，而不是 `status` 直接参与唯一键 | 一条 SQL 内同时满足“有效选课唯一”和“退课后可重选”，无需删除历史记录 |
| D26 | 课程模块只用一个 `course` 包（含学期/排课/选课/请假），不拆 `selection`、`leave` 独立包 | 三者共享课程、学期、排课实体与归属判断，拆包会产生大量跨包循环依赖 |
| D27 | 时间冲突判定抽成纯函数 `ScheduleConflict`，由课程排课与选课共用 | 一处规则、两处复用，可单元测试且不会出现“排课允许、选课拒绝”的不一致 |
| D28 | 工具不实现业务逻辑，只调用既有 Service；权限判断全部留在 Service | 避免出现“AI 专用路径”绕过既有规则；阶段二至六的并发与权限保证自动适用于工具 |
| D29 | 写操作工具采用“先落 PENDING_CONFIRM + 预览，再确认执行”，确认时用条件更新认领 | 用户能看到将要做什么；并发确认只执行一次；失败也留痕，可审计 |
| D30 | 工具参数使用 `Map<String,Object>` + 统一校验器，而不是为每个工具建请求 DTO | 工具参数结构由 `ToolParameter` 声明、运行时校验，新增工具不需要新增 DTO，模型提示词可直接由目录生成 |
| D31 | 后端不解析模型自由文本为工具调用，只提供工具目录与调用 API | 模型输出不可信，解析文本容易被提示注入利用；由编排层显式调用可保证每次写操作都经过确认 |
| D32 | 生产环境固定 `spring.flyway.clean-disabled=true`，迁移脚本只追加不修改（`validate-on-migrate=true`） | 防止误清库；已发布脚本被改动时启动即失败，而不是让环境间结构悄悄不一致 |
| D33 | 部署文档独立为 `docs/06-deployment.md`，配置项一律提供环境变量入口，`.env.example` 为唯一模板 | 交付物与代码同仓，部署时不需要读源码；环境变量便于容器化与密钥注入 |
| D34 | 部署冒烟脚本只验证“写工具停在待确认 + 拒绝后无数据”，不执行确认 | 冒烟通常跑在生产库，确认执行会留下业务数据；确认路径由集成测试覆盖 |

## 6. 业务假设（如有不同请指出，将据此调整）

| # | 假设 |
|---|---|
| A1 | 登录名使用学号或工号，一个账号可以同时拥有多个角色（例如教师兼管理员） |
| A2 | 组织单元为树形结构（学院、系、专业、班级），用户归属一个组织单元。“学院级”知识库对该组织及其下级组织的用户可见 |
| A3 | 知识库可见范围分为 `PUBLIC`（全体登录用户）、`ORG`（指定组织及其下级）、`COURSE`（课程的任课教师和已选课学生）、`PRIVATE`（仅成员）。成员授权（MANAGER/EDITOR/VIEWER）可以叠加在任意范围之上 |
| A4 | 教师能否创建知识库由权限点 `kb:space:create` 控制（默认授予）。教师只能管理自己创建的知识库或被授予 MANAGER/EDITOR 的知识库 |
| A5 | 课程以“学期 + 课程代码”为唯一的开课记录；一门课可以有多名授课教师（主讲/助教），主讲和助教都可以审批该课程的请假，只有主讲可以修改课程信息 |
| A6 | 节次为 1–12 的整数，周次为 1–30，支持单双周；时间冲突按“同一学期、同一星期、节次区间重叠、周次区间重叠且单双周相交”判定 |
| A7 | 选课仅在学期设定的选课时间窗口内允许；退课时间窗口第一版与选课相同 |
| A8 | 请假申请由该课程的授课教师审批；学生只能在 PENDING 状态下撤销申请。审批后不可修改，如需变更须重新提交 |
| A9 | 论坛帖子作者可以修改和删除自己的帖子；删除采用软删除，管理员可以隐藏或恢复。评论支持两级结构（评论加楼中楼回复） |
| A10 | 用户不物理删除，只停用；与用户关联的业务数据保留，确保可以追溯 |
| A11 | AI 工具由调用方（前端 Agent 或模型编排层）通过 `/ai/tool-calls` 显式调用；写操作必须经过用户确认，确认有效期默认 10 分钟 |

