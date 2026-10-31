# 04 API 规范与规划

## 1. 通用规范

- 前缀：`/api/v1`。资源名用复数名词，多个单词用 kebab-case（如 `/course-selections`）。
- 方法：`GET` 查询，`POST` 创建或执行动作，`PUT` 整体更新，`PATCH` 局部更新或状态变更，`DELETE` 删除。非 CRUD 的动作使用子资源，例如 `POST /leave-requests/{id}/approve`。
- 认证：`Authorization: Bearer <accessToken>`。没有令牌或令牌无效返回 401，没有权限返回 403。
- 入参和出参一律使用 DTO，不直接返回实体；永不返回 `password_hash` 等敏感字段。
- 时间格式：ISO-8601。请求中的 `LocalDateTime` 按业务时区（Asia/Shanghai）解释；业务对象的时间字段使用该时区的本地时间，令牌过期时间等瞬时值带偏移量。
- 每个响应都带响应头 `X-Trace-Id`。

### 统一响应体

```json
{
  "success": true,
  "code": "OK",
  "message": "success",
  "data": { },
  "traceId": "f61911e8643044599af54c8d421bcc21",
  "timestamp": "2026-10-26T20:54:13.677+08:00"
}
```

失败示例（参数校验失败）：

```json
{
  "success": false,
  "code": "VALIDATION_FAILED",
  "message": "参数校验失败",
  "errors": [ { "field": "title", "message": "标题不能为空" } ],
  "traceId": "...",
  "timestamp": "..."
}
```

### 错误码与 HTTP 状态

| code | HTTP | 场景 |
|---|---|---|
| BAD_REQUEST / VALIDATION_FAILED / KB_DOCUMENT_INVALID_FILE / KB_DOCUMENT_EMPTY | 400 | 请求格式错误、参数校验失败、原密码错误、密码强度不足、未知角色、非法 Markdown 文件/空文档 |
| UNAUTHORIZED / AUTH_BAD_CREDENTIALS | 401 | 未登录、令牌失效、用户名或密码错误 |
| FORBIDDEN / AUTH_ACCOUNT_DISABLED / AUTH_ACCOUNT_LOCKED | 403 | 无权限、账号停用、管理员锁定或连续失败临时锁定 |
| NOT_FOUND | 404 | 资源不存在。对无权访问的私有资源/停用组织也返回 404，避免泄露存在性 |
| METHOD_NOT_ALLOWED | 405 | |
| CONFLICT / KB_CATEGORY_IN_USE | 409 | 用户名/邮箱/组织编码/分类名重复、乐观锁版本冲突、分类仍被引用 |
| FORUM_CATEGORY_IN_USE | 409 | 论坛分类下仍有帖子（含已删除），不能删除 |
| PAYLOAD_TOO_LARGE | 413 | 上传超过大小限制 |
| UNSUPPORTED_MEDIA_TYPE | 415 | |
| BUSINESS_RULE_VIOLATION / USER_SELF_OPERATION_FORBIDDEN / USER_LAST_ADMIN / ORG_INVALID_PARENT / ORG_HAS_ACTIVE_CHILDREN / ORG_UNAVAILABLE / KB_CATEGORY_DEPTH_EXCEEDED / KB_SPACE_INVALID_VISIBILITY / KB_SPACE_ORG_REQUIRED | 422 | 业务规则不满足：操作自己、最后一个管理员、组织/分类层级、分类过深、可见范围非法、知识库组织范围缺失等 |
| FORUM_CATEGORY_DISABLED / FORUM_POST_STATUS_INVALID / FORUM_COMMENT_PARENT_INVALID | 422 | 论坛分类已停用、帖子状态变更非法、父评论不属于该帖子或已删除 |
| SEMESTER_CLOSED / COURSE_STATUS_INVALID / COURSE_CAPACITY_INVALID / COURSE_SCHEDULE_INVALID / COURSE_SCHEDULE_CONFLICT / COURSE_TEACHER_REQUIRED / COURSE_TEACHER_ROLE_INVALID | 422 | 学期已结束、课程状态变更非法、容量小于已选人数、排课参数/时间冲突、授课教师角色不合法 |
| SELECTION_WINDOW_CLOSED / SELECTION_COURSE_NOT_OPEN / SELECTION_COURSE_FULL / SELECTION_TIME_CONFLICT / SELECTION_NOT_ENROLLED | 422 | 不在选课窗口、课程未开放、容量已满、与已选课程时间冲突、未选该课程 |
| SELECTION_DUPLICATED | 409 | 已经选择过该课程（含并发重复选课） |
| LEAVE_STATUS_INVALID / LEAVE_TIME_INVALID | 422 | 请假时间非法（起止颠倒、超出学期、排课不属于课程）；状态变更非法由 409 表达 |
| AI_TOOL_ARGUMENT_INVALID | 400 | 工具参数缺参、类型不符、超长、枚举越界 |
| AI_TOOL_CONFIRM_EXPIRED | 409 | 待确认的工具调用已超过确认截止时间 |
| AI_TOOL_STATUS_INVALID | 409 | 工具调用当前状态不允许确认/拒绝（已执行、已拒绝、并发确认失败） |
| TOO_MANY_REQUESTS | 429 | 登录/改密限流；响应头 `Retry-After` 为建议等待秒数 |
| INTERNAL_ERROR | 500 | 未预期错误，详情只记录在服务端日志 |
| SERVICE_UNAVAILABLE | 503 | 模型或外部服务不可用 |

### 分页

请求：`?page=1&size=20`（页码从 1 开始，size 取 1–100，超出范围返回 400）。响应 `data`：

```json
{ "items": [], "total": 0, "page": 1, "size": 20, "pages": 0 }
```

## 2. 已实现接口（阶段一至七）

### 2.1 公共与系统

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/v1/system/info` | 公开 | 服务名、API 版本、服务器时间、业务时区 |
| GET | `/actuator/health` | 公开 | 健康检查（不显示细节） |
| GET | `/swagger-ui.html`、`/v3/api-docs` | 公开（可关闭） | 接口文档 |
| GET | `/api/v1/auth/me` | 登录 | 当前用户、角色、权限点 |
| GET | `/api/v1/audit-logs` | `audit:read` | 审计日志分页；筛选条件：actorUserId、module、action、source、result、targetType、targetId、from、to |

### 2.2 认证

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/v1/auth/login` | 公开 | 登录，返回 JWT 与当前用户；`Cache-Control: no-store` |
| POST | `/api/v1/auth/logout` | 登录 | 当前用户 `token_version` 自增，所有设备令牌立即失效 |
| PUT | `/api/v1/auth/password` | 登录 | 修改本人密码；成功后旧令牌失效并返回新令牌 |
| GET | `/api/v1/auth/me` | 登录 | 当前用户、角色、权限点（每次实时从数据库加载） |

登录请求：

```json
{ "username": "20260001", "password": "********" }
```

成功响应 `data`：

```json
{
  "accessToken": "<JWT>",
  "tokenType": "Bearer",
  "expiresIn": 7200,
  "expiresAt": "2026-10-27T22:30:00+08:00",
  "user": {
    "userId": 2,
    "username": "20260001",
    "displayName": "张三",
    "orgUnitId": 10,
    "roles": ["STUDENT"],
    "permissions": [
      "ai:chat", "ai:tool:use", "course:read", "forum:comment:write",
      "forum:post:write", "kb:space:read", "leave:submit", "selection:select"
    ]
  }
}
```

错误行为：
- 用户名不存在或密码错误：401 `AUTH_BAD_CREDENTIALS`，两者提示相同。
- 账号停用：403 `AUTH_ACCOUNT_DISABLED`；管理员锁定或连续失败临时锁定：403 `AUTH_ACCOUNT_LOCKED`。停用/锁定提示只在密码正确后返回。
- 超过限流：429 `TOO_MANY_REQUESTS`，响应头 `Retry-After` 为建议等待秒数。
- 登录成功/失败、临时锁定、退出、改密均写审计，detail 不包含密码或令牌。

修改密码请求：

```json
{ "oldPassword": "********", "newPassword": "********" }
```

成功响应与登录成功相同（返回新的 `accessToken`）。原密码错误返回 400 `AUTH_PASSWORD_MISMATCH`；强度不足或与原密码相同返回 400 `PASSWORD_TOO_WEAK`。

### 2.3 用户管理（管理员）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/v1/users` | `user:read` | 分页查询；支持 `keyword`、`status`、`orgUnitId`、`includeSubOrgs`、`roleCode`、`page`、`size` |
| GET | `/api/v1/users/{id}` | `user:read` | 用户详情（含角色、组织名、锁定状态、version） |
| POST | `/api/v1/users` | `user:manage` + `role:manage` | 创建用户 |
| PUT | `/api/v1/users/{id}` | `user:manage` | 修改资料；必须回传 `version`，冲突返回 409 |
| PATCH | `/api/v1/users/{id}/status` | `user:manage` | 启用/停用/锁定；`reason` 可空 |
| POST | `/api/v1/users/{id}/unlock` | `user:manage` | 解除连续失败临时锁定，清零计数 |
| PUT | `/api/v1/users/{id}/roles` | `role:manage` | 整体替换角色，可为空集合 |
| POST | `/api/v1/users/{id}/password-reset` | `user:manage` | 管理员重置密码，旧令牌全部失效 |

创建用户请求：

```json
{
  "username": "20260002",
  "displayName": "李四",
  "password": "********",
  "email": "20260002@campus.test",
  "phone": "13800000000",
  "orgUnitId": 10,
  "roleCodes": ["STUDENT"]
}
```

- 用户名、邮箱大小写不敏感唯一；邮箱可空。
- 用户名格式、手机号格式、角色编码格式由 Bean Validation 校验。
- 密码必须通过密码策略；未知角色返回 400，停用/不存在组织返回 422 `ORG_UNAVAILABLE`。
- 响应永远不包含 `passwordHash`、`tokenVersion`。

修改资料请求（整体更新）：

```json
{ "displayName": "李四", "email": "lisi@campus.test", "phone": null, "orgUnitId": 10, "version": 0 }
```

状态请求：`{ "status": "ACTIVE|DISABLED|LOCKED", "reason": "可选" }`。分配角色请求：`{ "roleCodes": ["TEACHER"] }`。重置密码请求：`{ "newPassword": "********" }`。

保护规则：不能停用/锁定自己、修改自己的角色或重置自己的密码；不能停用/锁定最后一个可用管理员或移除其 ADMIN 角色；不能管理权限高于自己的用户，也不能授予超出自身权限的角色。

### 2.4 角色与权限（只读）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/v1/roles` | `user:read` 或 `role:manage` | 内置角色及其权限点 |
| GET | `/api/v1/permissions` | `role:manage` | 全部权限点 |

### 2.5 组织单元

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/v1/org-units/tree` | 登录 | 启用的组织树；`includeDisabled=true` 需要 `org:manage` |
| GET | `/api/v1/org-units/{id}` | 登录 | 组织详情；已停用组织需要 `org:manage`，否则 404 |
| POST | `/api/v1/org-units` | `org:manage` | 新建组织 |
| PUT | `/api/v1/org-units/{id}` | `org:manage` | 整体修改（含移动上级）；不能移动到自身或下级 |
| PATCH | `/api/v1/org-units/{id}/status` | `org:manage` | 启用/停用；停用前须先停用全部下级，启用时上级须启用 |

新建/修改请求：

```json
{ "parentId": 1, "code": "CS", "name": "计算机学院", "type": "COLLEGE", "sortOrder": 0 }
```

`type` 为 `COLLEGE`、`DEPARTMENT`、`MAJOR`、`CLASS`，下级 rank 必须大于上级（可跨级）。组织编码大小写不敏感唯一。组织不物理删除。

### 2.6 角色访问矩阵

| 接口组 | ADMIN | TEACHER | STUDENT | 无角色用户 |
|---|:-:|:-:|:-:|:-:|
| `/auth/me`、启用的组织树/详情 | ✓ | ✓ | ✓ | ✓ |
| 用户查询/详情 | ✓ | ✗ | ✗ | ✗ |
| 用户创建/修改/状态/解锁/重置密码 | ✓ | ✗ | ✗ | ✗ |
| 角色列表、权限点列表 | ✓ | ✗ | ✗ | ✗ |
| 审计日志 | ✓ | ✗ | ✗ | ✗ |
| 组织创建/修改/启停、`includeDisabled=true` | ✓ | ✗ | ✗ | ✗ |

未登录访问受保护接口统一返回 401 JSON；无权限返回 403。更细的“不能管理更高权限用户”“最后一个管理员”等规则见第 2.3 节。

### 2.7 知识库（阶段三）

#### 分类

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/v1/knowledge/categories` | `kb:space:read` | 分类树（最多两级） |
| POST | `/api/v1/knowledge/categories` | `kb:category:manage` | 新建分类 |
| PUT | `/api/v1/knowledge/categories/{id}` | `kb:category:manage` | 修改分类；有子分类时不能移动 |
| DELETE | `/api/v1/knowledge/categories/{id}` | `kb:category:manage` | 删除分类；不能有子分类或空间 |

请求：`{ "parentId": null, "name": "新生", "description": "新生指南", "sortOrder": 0 }`。

#### 空间与成员

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/v1/knowledge/spaces` | `kb:space:read` | 当前用户可访问的空间分页；支持 `keyword`、`categoryId`、`visibility`、`includeArchived` |
| GET | `/api/v1/knowledge/spaces/{id}` | `kb:space:read` | 空间详情与 `accessLevel` |
| POST | `/api/v1/knowledge/spaces` | `kb:space:create` 或 `kb:space:manage_all` | 创建空间；PUBLIC 仅管理员可建 |
| PUT | `/api/v1/knowledge/spaces/{id}` | owner/manager | 修改空间；必须回传 `version` |
| DELETE | `/api/v1/knowledge/spaces/{id}` | owner/manager | 软删除空间 |
| GET | `/api/v1/knowledge/spaces/{id}/members` | owner/manager | 成员列表 |
| POST | `/api/v1/knowledge/spaces/{id}/members` | owner/manager | 添加或更新成员角色 |
| PUT | `/api/v1/knowledge/spaces/{id}/members/{userId}` | owner/manager | 修改成员角色 |
| DELETE | `/api/v1/knowledge/spaces/{id}/members/{userId}` | owner/manager | 移除成员；不能移除 owner |

创建空间请求：

```json
{
  "categoryId": 1,
  "name": "SJTU Wiki",
  "description": "上海交通大学校园百科",
  "visibility": "PUBLIC",
  "scopeOrgUnitId": null
}
```

`visibility` 为 `PUBLIC/ORG/PRIVATE/COURSE`；`ORG` 必须提供 `scopeOrgUnitId`，并受 `DataScopeService` 约束；`COURSE` 必须提供 `scopeCourseId`，且创建/修改者必须是该课程的任课教师或管理员（详见 2.12）。成员请求：`{ "userId": 2, "memberRole": "VIEWER" }`，角色为 `MANAGER/EDITOR/VIEWER`。

#### 文档、版本与分块

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/v1/knowledge/spaces/{spaceId}/documents` | `kb:space:read` | 文档分页；支持 `keyword`、`processStatus`、`status` |
| POST | `/api/v1/knowledge/spaces/{spaceId}/documents` | `kb:document:write` + 空间写权限 | 在线新建 Markdown |
| POST | `/api/v1/knowledge/spaces/{spaceId}/documents/batch-upload` | `kb:document:write` + 空间写权限 | multipart 字段 `files`，逐文件处理 |
| GET | `/api/v1/knowledge/upload-batches/{batchId}` | `kb:space:read` | 批次详情与每个文件成功/失败结果 |
| GET | `/api/v1/knowledge/documents/{id}` | `kb:space:read` | 文档详情（含当前版本原文） |
| PUT | `/api/v1/knowledge/documents/{id}` | `kb:document:write` + 空间写权限 | 生成新版本；必须回传 `version` |
| DELETE | `/api/v1/knowledge/documents/{id}` | `kb:document:write` + 空间写权限 | 软删除，旧块 STALE |
| POST | `/api/v1/knowledge/documents/{id}/reprocess` | `kb:document:write` + 空间写权限 | 重建当前版本块，不新增版本 |
| GET | `/api/v1/knowledge/documents/{id}/chunks` | `kb:space:read` | 当前版本块（含 headingPath、状态） |
| GET | `/api/v1/knowledge/documents/{id}/versions` | `kb:space:read` | 版本列表 |
| GET | `/api/v1/knowledge/documents/{id}/versions/{versionNo}` | `kb:space:read` | 指定版本原文 |

在线新建请求：

```json
{
  "title": "入学准备",
  "content": "# 入学准备\n\n正文……",
  "changeNote": "初始版本",
  "effectiveFrom": null,
  "effectiveTo": null
}
```

批量上传响应 `data`：

```json
{
  "batchId": 12,
  "spaceId": 3,
  "totalCount": 2,
  "successCount": 1,
  "failedCount": 1,
  "status": "PARTIAL",
  "items": [
    { "filename": "preparation.md", "success": true, "documentId": 42, "title": "入学准备", "error": null },
    { "filename": "notes.txt", "success": false, "documentId": 43, "title": null, "error": "只支持 .md 文件" }
  ]
}
```

状态：`processStatus` 为 `PENDING/PROCESSING/INDEXED/FAILED`，批次为 `PROCESSING/COMPLETED/PARTIAL/FAILED`；失败文件也会保留文档记录（`processStatus=FAILED`）便于追踪。文档编辑和删除只对 `ACTIVE` 文档生效；列表默认只返回 `ACTIVE`。默认限制：单文件 2MB、单批 20 个文件（`APP_KB_MAX_FILE_BYTES`、`APP_KB_MAX_FILES_PER_BATCH` 可配置）。

### 2.8 知识库访问矩阵

| 接口组 | ADMIN / manage_all | 空间 owner/manager | 空间 editor | 空间 viewer / PUBLIC / ORG 读者 | 无权限 |
|---|:-:|:-:|:-:|:-:|:-:|
| 分类读取、空间列表/详情、文档列表/详情/版本/块 | ✓ | ✓ | ✓ | ✓ | ✗ |
| 创建空间（PUBLIC） | ✓ | ✗ | ✗ | ✗ | ✗ |
| 修改/删除空间、成员管理 | ✓ | ✓ | ✗ | ✗ | ✗ |
| 在线新建/编辑/删除文档、批量上传、重处理 | ✓ | ✓ | ✓ | ✗ | ✗ |

“空间 owner/manager”仍必须具备 `kb:document:write` 才能写文档；成员身份不会绕过功能权限。跨组织不可见统一返回 404，避免探测。

### 2.9 知识库检索与 AI 问答

#### 关键词检索

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/v1/knowledge/search` | `kb:space:read` | 在当前用户有权访问的空间内检索；权限过滤先于检索 |

请求：

```json
{ "query": "报到需要带什么？", "spaceIds": [1], "topK": 5, "minScore": 0.0 }
```

`spaceIds` 为空表示所有可读空间；`topK` 默认 5、最大 20；`minScore` 为空时取服务端配置。中文自然语言会拆成 2 字 ngram 后检索，标题命中权重 ×4，正文/章节命中权重 ×1；结果按内容哈希去重。

响应 `data`：

```json
{
  "query": "报到需要带什么？",
  "strategy": "KEYWORD",
  "candidateCount": 8,
  "returnedCount": 2,
  "insufficientEvidence": false,
  "maxScore": 0.0000000123,
  "hits": [
    {
      "chunkId": 101,
      "documentId": 12,
      "versionId": 12,
      "spaceId": 1,
      "spaceName": "SJTU Wiki",
      "documentTitle": "入学准备",
      "headingPath": "入学准备 > 重要时间节点",
      "snippet": "报到时请携带录取通知书……",
      "content": "报到时请携带录取通知书……",
      "score": 0.0000000123,
      "rank": 1
    }
  ]
}
```

无结果时 `insufficientEvidence=true`、`hits=[]`。MySQL ngram 的绝对分数较小，默认 `minScore=0.0` 表示只过滤非正分；需要严格过滤时由调用方传 `minScore`。后续 Qdrant 向量检索会使用同一 `strategy`/响应结构。

#### AI 问答

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/v1/ai/chat` | `ai:chat` | 检索 + 模型回答；返回回答、引用、Token |
| GET | `/api/v1/ai/conversations` | `ai:chat` | 本人会话分页 |
| GET | `/api/v1/ai/conversations/{id}` | `ai:chat` | 会话详情（消息与引用），仅本人 |
| DELETE | `/api/v1/ai/conversations/{id}` | `ai:chat` | 软删除本人会话 |

请求：

```json
{
  "message": "报到需要带什么？",
  "conversationId": null,
  "spaceIds": [1]
}
```

响应 `data`：

```json
{
  "conversationId": 3,
  "userMessageId": 7,
  "assistantMessageId": 8,
  "answer": "根据资料，报到时需要携带录取通知书。[1]",
  "sufficientEvidence": true,
  "model": "deepseek-chat",
  "citations": [
    {
      "chunkId": 101,
      "documentId": 12,
      "versionId": 12,
      "spaceId": 1,
      "spaceName": "SJTU Wiki",
      "documentTitle": "入学准备",
      "headingPath": "入学准备 > 重要时间节点",
      "snippet": "报到时请携带录取通知书……",
      "score": 0.0000000123,
      "rankNo": 1
    }
  ],
  "tokenUsage": { "promptTokens": 120, "completionTokens": 30, "totalTokens": 150, "source": "REAL" }
}
```

- 检索不足：不调用模型，`sufficientEvidence=false`，回答明确说明“根据当前知识库没有找到足够依据…”，消息状态 `NO_EVIDENCE`，引用为空。
- 未配置模型（`AI_CHAT_PROVIDER=none` 或没有 ChatModel）：返回 503 `SERVICE_UNAVAILABLE`，保存 `FAILED` 消息，不生成回答、不写引用。
- 模型调用失败：同样返回 503，保存失败消息；可查看会话历史定位。
- `tokenUsage.source` 为 `REAL`（模型返回 usage）或 `ESTIMATED`（按字符估算）。
- 会话只能本人访问；其他用户访问返回 404。

### 2.10 检索/问答访问矩阵

| 接口 | ADMIN | TEACHER | STUDENT | 无 ai:chat / 无角色 |
|---|:-:|:-:|:-:|:-:|
| `POST /knowledge/search` | ✓（可读空间） | ✓（可读空间） | ✓（可读空间） | ✗ |
| `POST /ai/chat` | ✓ | ✓ | ✓ | ✗ |
| 本人会话列表/详情/删除 | ✓ | ✓ | ✓ | ✗ |

所有角色的内置权限都包含 `kb:space:read` 与 `ai:chat`；实际可见内容仍由知识库空间权限决定。

### 2.11 论坛（阶段五）

#### 分类

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/v1/forum/categories` | 登录 | 分类列表；默认只返回 ACTIVE，`includeDisabled=true` 需要 `forum:category:manage` |
| GET | `/api/v1/forum/categories/{id}` | 登录 | 分类详情；停用分类对普通用户返回 404，管理员可读 |
| POST | `/api/v1/forum/categories` | `forum:category:manage` | 新建分类 |
| PUT | `/api/v1/forum/categories/{id}` | `forum:category:manage` | 修改名称/描述/排序/状态（停用/启用） |
| DELETE | `/api/v1/forum/categories/{id}` | `forum:category:manage` | 删除分类；分类下不能有任何帖子（含已删除） |

请求：`{ "name": "校园生活", "description": "新生答疑", "sortOrder": 0, "status": "ACTIVE" }`。`status` 为 `ACTIVE/DISABLED`，新建时默认 `ACTIVE`。

#### 帖子

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/v1/forum/posts` | 登录 | 分页/关键词检索；参数 `categoryId`、`keyword`、`authorId`、`status`、`page`、`size` |
| GET | `/api/v1/forum/posts/{id}` | 登录 | 详情；隐藏帖子仅作者本人和版主可见，删除/停用分类返回 404 |
| POST | `/api/v1/forum/posts` | `forum:post:write` | 发帖 |
| PUT | `/api/v1/forum/posts/{id}` | `forum:post:write` 或 `forum:moderate` | 整体修改；作者或版主；必须回传 `version` |
| DELETE | `/api/v1/forum/posts/{id}` | `forum:post:write` 或 `forum:moderate` | 软删除；作者或版主 |
| PATCH | `/api/v1/forum/posts/{id}/status` | `forum:moderate` | 隐藏、恢复、置顶；`status` 与 `pinned` 至少提供一个 |

发帖请求：

```json
{ "categoryId": 1, "title": "选课求助", "content": "请问……" }
```

修改请求：`{ "categoryId": 1, "title": "……", "content": "……", "version": 0 }`。`version` 不匹配返回 409。

状态请求：`{ "status": "HIDDEN", "pinned": false, "reason": "重复内容" }`。`status` 只能是 `PUBLISHED`（恢复）或 `HIDDEN`（隐藏），不能通过该接口改成 `DELETED`。

列表响应 `data` 为分页结构，`items` 元素：

```json
{
  "id": 12,
  "categoryId": 1,
  "categoryName": "校园生活",
  "authorId": 7,
  "authorName": "张三",
  "title": "选课求助",
  "excerpt": "请问……",
  "status": "PUBLISHED",
  "pinned": false,
  "commentCount": 3,
  "lastActivityAt": "2026-10-27T21:00:00",
  "createdAt": "2026-10-27T20:00:00",
  "updatedAt": "2026-10-27T20:00:00",
  "version": 0
}
```

列表排序：置顶优先，其次 `lastActivityAt` 倒序；有关键词时同等置顶下按全文相关度排序。默认只返回 `PUBLISHED`；非版主传 `status=HIDDEN` 返回 403，传 `status=DELETED` 返回 422。

#### 评论

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/v1/forum/posts/{id}/comments` | 登录 | 按顶级评论分页，每个线程带全部回复；参数 `page`、`size` |
| POST | `/api/v1/forum/posts/{id}/comments` | `forum:comment:write` | 发表顶级评论（`parentId` 为空）或回复 |
| DELETE | `/api/v1/forum/comments/{id}` | `forum:comment:write` 或 `forum:moderate` | 删除评论；作者或版主 |

请求：`{ "content": "我也想知道", "parentId": 3 }`。`parentId` 为空表示顶级评论；回复一条回复时仍归入同一顶级评论线程，接口返回的 `rootId` 指向顶级评论。

评论线程响应 `data`：

```json
{
  "items": [
    {
      "comment": {
        "id": 3, "postId": 12, "parentId": null, "rootId": null,
        "authorId": 7, "authorName": "张三", "replyToUserId": null,
        "replyToUserName": null, "content": "我也想知道",
        "status": "PUBLISHED", "createdAt": "2026-10-27T20:30:00"
      },
      "replies": [
        {
          "id": 4, "postId": 12, "parentId": 3, "rootId": 3,
          "authorId": 8, "authorName": "李四", "replyToUserId": 7,
          "replyToUserName": "张三", "content": "同问",
          "status": "PUBLISHED", "createdAt": "2026-10-27T20:31:00"
        }
      ]
    }
  ],
  "total": 1, "page": 1, "size": 20, "pages": 1
}
```

- 只有 `PUBLISHED` 帖子接受新评论；隐藏/已删除/停用分类下的帖子返回 404。
- 删除顶级评论会级联软删除其全部回复；删除单条回复只影响该回复，其下的“回复的回复”仍保留。
- 跨帖子的 `parentId` 或已删除父评论返回 422 `FORUM_COMMENT_PARENT_INVALID`。

#### 论坛访问矩阵

| 接口组 | ADMIN | TEACHER | STUDENT | 无角色用户 |
|---|:-:|:-:|:-:|:-:|
| 分类/帖子/评论读取 | ✓ | ✓ | ✓ | ✓ |
| 发帖、修改/删除本人帖子 | ✓ | ✓ | ✓ | ✗ |
| 评论、删除本人评论 | ✓ | ✓ | ✓ | ✗ |
| 分类管理（含 includeDisabled） | ✓ | ✗ | ✗ | ✗ |
| 隐藏/恢复/置顶、修改/删除任意帖子、删除任意评论 | ✓ | ✗ | ✗ | ✗ |
| 查看 `status=HIDDEN` 列表 | ✓ | ✗ | ✗ | ✗ |

论坛没有单独的 `forum:read` 权限点：登录即可读取；写操作由 `forum:post:write`、`forum:comment:write`、`forum:moderate`、`forum:category:manage` 控制。读不到或已删除的资源统一返回 404。

### 2.12 课程、排课、选课与请假（阶段六）

#### 学期

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/v1/semesters` | `course:read` | 学期列表，按开始日期倒序 |
| GET | `/api/v1/semesters/current` | `course:read` | 当前学期：优先返回今天在起止日期内的 ACTIVE 学期，否则最近一个 ACTIVE；没有则 `data=null` |
| GET | `/api/v1/semesters/{id}` | `course:read` | 学期详情，含 `selectionOpen` 与 `courseCount` |
| POST | `/api/v1/semesters` | `course:manage_all` | 新建学期，默认 `DRAFT` |
| PUT | `/api/v1/semesters/{id}` | `course:manage_all` | 修改学期；`CLOSED` 学期不可修改 |
| PATCH | `/api/v1/semesters/{id}/status` | `course:manage_all` | `DRAFT→ACTIVE`、`DRAFT/ACTIVE→CLOSED`；`CLOSED` 为终态 |

请求：

```json
{
  "code": "2026-2027-1",
  "name": "2026-2027 学年第一学期",
  "startDate": "2026-09-07",
  "endDate": "2027-01-15",
  "selectionStartAt": "2026-08-20T09:00:00",
  "selectionEndAt": "2026-09-10T18:00:00"
}
```

`selectionOpen` 只有在“学期 `ACTIVE` 且当前时间落在选课窗口内”时为 `true`；`DRAFT` 学期即使时间在窗口内也不允许选课。

#### 课程

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/v1/courses` | `course:read` | 分页；参数 `semesterId`、`keyword`、`status`、`teacherId`、`orgUnitId`；默认排除 `CANCELLED` |
| GET | `/api/v1/courses/available` | `course:read` | 可选课程：本学期 `OPEN` 课程，逐条返回 `selectable` 与 `blockedReason` |
| GET | `/api/v1/courses/{id}` | `course:read` | 详情：教师、排课、本人选课状态、不可选原因；`CANCELLED` 对普通用户 404 |
| POST | `/api/v1/courses` | `course:manage_own` 或 `course:manage_all` | 创建课程，创建人自动成为主讲 |
| PUT | `/api/v1/courses/{id}` | 主讲或 `course:manage_all` | 修改名称/学分/简介/组织/容量；必须回传 `version` |
| PATCH | `/api/v1/courses/{id}/status` | 主讲或 `course:manage_all` | 开放/关闭/停开 |
| GET | `/api/v1/courses/{id}/teachers` | `course:read` | 授课教师列表 |
| POST | `/api/v1/courses/{id}/teachers` | 主讲或 `course:manage_all` | 添加教师；改为主讲需要 `course:manage_all`，原主讲自动转助教 |
| DELETE | `/api/v1/courses/{id}/teachers/{teacherId}` | 主讲或 `course:manage_all` | 移除教师；主讲必须先改派 |
| GET | `/api/v1/courses/{id}/selections` | 任课教师或管理员 | 选课名单（只含有效选课），分页 |

创建课程请求：

```json
{ "semesterId": 3, "code": "CS101", "name": "计算机导论", "credit": 3.0,
  "description": "面向大一新生", "orgUnitId": null, "capacity": 60 }
```

`orgUnitId` 为空时取创建人所属组织。`UNIQUE(semester_id, code)`：同学期课程编码重复返回 409；学期 `CLOSED` 返回 422 `SEMESTER_CLOSED`。

状态请求：`{ "status": "OPEN|CLOSED|CANCELLED", "reason": "可选" }`。`OPEN` 前必须已排课，否则 422 `COURSE_STATUS_INVALID`；`CANCELLED` 为终态，会级联退掉全部有效选课、清零 `selectedCount`、撤销该课程待审批请假。

可选课程响应 `data`（数组）：

```json
[
  {
    "courseId": 12, "semesterId": 3, "code": "CS101", "name": "计算机导论",
    "credit": 3.0, "capacity": 60, "selectedCount": 12, "status": "OPEN",
    "teacherNames": ["王老师"],
    "schedules": [{ "id": 5, "courseId": 12, "dayOfWeek": 1, "startPeriod": 1,
                    "endPeriod": 2, "startWeek": 1, "endWeek": 16,
                    "weekParity": "ALL", "classroom": "A101" }],
    "selectable": false,
    "blockedReason": "TIME_CONFLICT",
    "conflictCourseName": "高等数学"
  }
]
```

`blockedReason` 取值：`WINDOW_CLOSED`、`COURSE_NOT_OPEN`、`ALREADY_SELECTED`、`FULL`、`TIME_CONFLICT`；可选时为 `null`。

#### 排课与课表

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/v1/courses/{courseId}/schedules` | 主讲或 `course:manage_all` | 新增排课，返回该课程全部排课 |
| PUT | `/api/v1/courses/{courseId}/schedules/{scheduleId}` | 主讲或 `course:manage_all` | 修改排课 |
| DELETE | `/api/v1/courses/{courseId}/schedules/{scheduleId}` | 主讲或 `course:manage_all` | 删除排课 |
| GET | `/api/v1/courses/{id}/schedules` | `course:read` | 排课列表 |
| GET | `/api/v1/schedules/me?semesterId=` | `course:read` | 我的课表：学生返回已选课程、教师返回任教课程，合并去重后按星期/节次排序 |

排课请求：

```json
{ "dayOfWeek": 1, "startPeriod": 1, "endPeriod": 2,
  "startWeek": 1, "endWeek": 16, "weekParity": "ALL", "classroom": "A101" }
```

- `dayOfWeek` 1–7、节次 1–12、周次 1–30；开始值不得大于结束值，否则 422 `COURSE_SCHEDULE_INVALID`。
- 冲突判定：同一天 + 节次区间相交 + 周次区间相交 + 单双周不互斥。与本课程已有排课冲突、或与该课程任课教师在本学期的其它课程冲突，返回 422 `COURSE_SCHEDULE_CONFLICT`（消息中包含冲突课程名）。单周（`ODD`）与双周（`EVEN`）可以共用同一时间段。

#### 选课

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/v1/course-selections` | `selection:select` | 选课 `{ "courseId": 12 }` |
| GET | `/api/v1/course-selections/me?semesterId=` | `course:read` | 本人已选课程 |
| DELETE | `/api/v1/course-selections/{id}` | `selection:select` | 退课；仅本人、仅 `SELECTED`、仅在选课窗口内 |

选课校验顺序：`selection:select` → 学生行锁 → 课程行锁 → 课程存在且未停开 → 学期 ACTIVE 且在窗口内（否则 422 `SELECTION_WINDOW_CLOSED`）→ 课程 `OPEN`（否则 422 `SELECTION_COURSE_NOT_OPEN`）→ 未重复选课（否则 409 `SELECTION_DUPLICATED`）→ 无时间冲突（否则 422 `SELECTION_TIME_CONFLICT`）→ 条件更新占名额（否则 422 `SELECTION_COURSE_FULL`）。

退课同样受选课窗口约束；退课后可以重新选课，历史记录保留（`DROPPED`）。

#### 请假

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/v1/leave-requests` | `leave:submit` | 提交请假 |
| GET | `/api/v1/leave-requests/me` | `leave:submit` | 本人请假记录，支持 `status`、`semesterId`、`courseId` |
| GET | `/api/v1/leave-requests/to-review` | `leave:review` 或 `leave:manage_all` | 待审批：教师只看自己任教课程，管理员看全部；默认只返回 `PENDING` |
| GET | `/api/v1/leave-requests/{id}` | 登录（申请人/任课教师/管理员） | 详情；其他用户 404 |
| POST | `/api/v1/leave-requests/{id}/approve` | `leave:review` 或 `leave:manage_all` | 通过，body 可选 `{ "comment": "..." }` |
| POST | `/api/v1/leave-requests/{id}/reject` | `leave:review` 或 `leave:manage_all` | 驳回 |
| POST | `/api/v1/leave-requests/{id}/cancel` | `leave:submit` | 申请人撤销，仅 `PENDING` |

提交请求：

```json
{ "courseId": 12, "scheduleId": 5,
  "startAt": "2026-10-28T10:00:00", "endAt": "2026-10-28T12:00:00",
  "reason": "身体不适需要就医" }
```

- 只能对本人已选课程请假，否则 422 `SELECTION_NOT_ENROLLED`。
- 起止时间必须有序且落在学期日期范围内；`scheduleId` 必须属于该课程；否则 422 `LEAVE_TIME_INVALID`。
- 状态机：`PENDING → APPROVED/REJECTED`（任课教师或管理员，且不能是申请人本人）、`PENDING → CANCELLED`（申请人）。终态再审批或撤销返回 409，并发审批只有一个成功。

响应 `data`：

```json
{
  "id": 7, "studentId": 21, "studentUsername": "20260001", "studentName": "张三",
  "courseId": 12, "courseCode": "CS101", "courseName": "计算机导论", "scheduleId": 5,
  "startAt": "2026-10-28T10:00:00", "endAt": "2026-10-28T12:00:00",
  "reason": "身体不适需要就医", "status": "APPROVED",
  "reviewerId": 9, "reviewerName": "王老师", "reviewComment": "已了解，注意休息",
  "reviewedAt": "2026-10-27T21:30:00",
  "canReview": false, "canCancel": false,
  "createdAt": "2026-10-27T20:00:00", "version": 1
}
```

`canReview` / `canCancel` 按当前请求者实时计算，便于前端与 AI 工具决定是否展示操作入口。

#### 课程访问矩阵

| 接口组 | ADMIN | TEACHER | STUDENT | 无角色用户 |
|---|:-:|:-:|:-:|:-:|
| 学期/课程/排课/课表读取 | ✓ | ✓ | ✓ | ✗ |
| 学期创建/修改/状态 | ✓ | ✗ | ✗ | ✗ |
| 创建课程（成为主讲） | ✓ | ✓ | ✗ | ✗ |
| 修改课程/状态/排课、教师管理 | ✓（全部课程） | 仅本人主讲课程 | ✗ | ✗ |
| 课程名单、待审批请假 | ✓ | 仅任教课程 | ✗ | ✗ |
| 选课/退课 | ✓ | ✗（无 `selection:select`） | ✓ | ✗ |
| 提交/撤销本人请假 | ✓ | ✗（无 `leave:submit`） | ✓ | ✗ |
| 审批请假 | ✓（全部） | 仅任教课程 | ✗ | ✗ |
| `status=CANCELLED` 课程列表/详情 | ✓ | 仅本人任教课程 | ✗ | ✗ |

知识库 `visibility=COURSE` 的可见范围也由本模块决定：该课程的任课教师与有效选课学生可读，退课后立即失效。

### 2.13 AI 工具调用（阶段七）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/v1/ai/tools` | `ai:tool:use` | 当前用户可用的工具目录（按功能权限过滤），含参数声明，可直接作为模型提示词中的能力清单 |
| POST | `/api/v1/ai/tool-calls` | `ai:tool:use` | 调用工具：只读工具立即执行；写操作工具生成待确认记录 |
| POST | `/api/v1/ai/tool-calls/{id}/confirm` | `ai:tool:use` | 确认执行（仅本人、仅待确认、未过期） |
| POST | `/api/v1/ai/tool-calls/{id}/reject` | `ai:tool:use` | 拒绝执行，body 可选 `{ "reason": "..." }` |
| GET | `/api/v1/ai/tool-calls` | `ai:tool:use` | 调用记录分页；本人记录，管理员可看全部并支持 `userId` 过滤；可按 `status`、`toolName` 过滤 |
| GET | `/api/v1/ai/tool-calls/{id}` | `ai:tool:use` | 调用详情；非本人且非管理员返回 404 |
| POST | `/api/v1/ai/act` | `ai:tool:use` | 自然语言办事：一句话交给模型选工具填参；只读可多轮、写操作只返回 `pending` |
| GET | `/api/v1/ai/acts` | `ai:tool:use` | 办事历史分页；本人记录倒序，管理员可看全部并可按 `userId` 过滤；每条带当时的步骤快照与确认单当前状态 |

调用请求：

```json
{ "tool": "selectCourse", "arguments": { "courseId": 12 }, "conversationId": null }
```

只读工具响应 `data`（立即执行）：

```json
{
  "id": 31, "toolName": "getMySchedule", "sideEffect": false,
  "arguments": { "semesterId": 3 }, "argumentSummary": "semesterId=3",
  "preview": null, "status": "SUCCEEDED",
  "resultSummary": "返回 1 条排课", "errorCode": null, "errorMessage": null,
  "confirmExpiresAt": null, "confirmedAt": null, "executedAt": "2026-10-27T22:30:00",
  "latencyMs": 12, "createdAt": "2026-10-27T22:30:00",
  "result": [ { "courseId": 12, "courseCode": "CS101", "dayOfWeek": 1, "startPeriod": 1, "endPeriod": 2 } ]
}
```

写操作工具响应 `data`（未执行，等待确认）：

```json
{
  "id": 32, "toolName": "selectCourse", "sideEffect": true,
  "arguments": { "courseId": 12 }, "argumentSummary": "courseId=12",
  "preview": "将选择课程《CS101 计算机导论》，当前 12/60 人；确认后占用一个名额",
  "status": "PENDING_CONFIRM", "resultSummary": null,
  "confirmExpiresAt": "2026-10-27T22:40:00", "confirmedAt": null, "executedAt": null,
  "latencyMs": null, "createdAt": "2026-10-27T22:30:00", "result": null
}
```

确认后 `status=SUCCEEDED`，`result` 为该工具的业务返回值（例如选课记录）；若业务规则不满足，确认接口返回对应错误码（如 409 `SELECTION_DUPLICATED`），同时记录置为 `FAILED` 并保存 `errorCode`。

- `status` 取值：`PENDING_CONFIRM`（待确认）、`EXECUTING`（已被某个请求认领，短暂状态）、`SUCCEEDED`、`FAILED`、`REJECTED`、`EXPIRED`。
- 确认有效期默认 10 分钟（`APP_AI_TOOL_CONFIRM_TTL`）；过期确认返回 409 `AI_TOOL_CONFIRM_EXPIRED` 并标记 `EXPIRED`。
- **过期在查询时结算**：`GET /ai/tool-calls` 与 `GET /ai/tool-calls/{id}` 会先把本人已过 `confirm_expires_at` 的 `PENDING_CONFIRM` 置为 `EXPIRED` 再返回。否则过期记录会一直显示为「待确认」，用户在界面上点它必然失败（早前版本的缺陷）。
- 并发确认只有一个请求成功，其余返回 409 `AI_TOOL_STATUS_INVALID`，不会重复执行业务动作。
- 工具目录中的 `requiredPermissions` 为“满足任意一个即可”；目录按当前用户权限过滤，因此学生看不到建课/审批类工具。

#### 自然语言办事 `POST /ai/act`

请求：`{ "message": "给大学物理（上）加一条排课：周四第 7-8 节，1 到 16 周，教室 B201" }`

模型不直接操作数据库，只输出一条“指令 JSON”，由服务端校验后调用工具：

| 模型输出 | 服务端行为 |
|---|---|
| `{"action":"call","tool":"...","arguments":{...},"message":"..."}` | 校验工具名与参数；只读工具经 `AiToolService.invoke` 立即执行，结果回灌给模型进入下一轮（最多 4 轮）；写工具生成 `PENDING_CONFIRM` 并立即返回 |
| `{"action":"ask","message":"..."}` | 信息不足，直接把问题回给用户 |
| `{"action":"answer","message":"..."}` | 已可作答，结束 |
| 非 JSON 文本 | 原样回显，`outcome=RAW_TEXT`，不执行任何动作 |

响应：

```json
{
  "reply": "为大学物理（上）新增周四第 7-8 节、1-16 周、教室 B201 的排课，提交后需你确认",
  "steps": [
    { "roundNo": 1, "toolName": "searchAvailableCourses", "title": "查询可选课程", "sideEffect": false,
      "arguments": { "semesterId": 1, "keyword": "大学物理" }, "status": "SUCCEEDED",
      "summary": "返回 1 门课程，其中 1 门可选", "errorCode": null, "errorMessage": null, "callId": 41 },
    { "roundNo": 2, "toolName": "updateCourseSchedule", "title": "排课", "sideEffect": true,
      "arguments": { "courseId": 6, "dayOfWeek": 4, "startPeriod": 7, "endPeriod": 8,
                     "startWeek": 1, "endWeek": 16, "weekParity": "ALL", "classroom": "B201" },
      "status": "PENDING_CONFIRM", "summary": null, "errorCode": null,
      "errorMessage": null, "callId": 42 }
  ],
  "pending": { "id": 42, "status": "PENDING_CONFIRM", "preview": "将为课程《PH102 大学物理（上）》的排课：周4 第7-8 节，第1-16 周（ALL），教室 B201" },
  "outcome": "WAITING_CONFIRM",
  "model": "deepseek-flash"
}
```

- `outcome`：`DONE`（已答复）/ `WAITING_CONFIRM`（等确认）/ `MAX_ROUNDS`（轮次用尽）/ `RAW_TEXT`（模型未按协议输出）。
- 写操作**只到确认单为止**，必须再调 `POST /ai/tool-calls/{id}/confirm`；确认执行仍走同一条 `AiToolService.confirm`，并发与过期规则不变。
- 每一步都对应一条 `ai_tool_call` 记录（`source=AI_TOOL` 写入审计），界面上的“经过”与审计日志可逐条对上。
- 权限不因走自然语言而放宽：可用工具按 `ai:tool:use` + 工具声明的功能权限过滤，最终仍由业务 Service 判断数据归属。

#### 工具清单（阶段七）

| 工具 | 类型 | 复用 Service | 需要的权限 |
|---|---|---|---|
| `searchKnowledge` / `listKnowledgeDocuments` / `getKnowledgeDocument` | 只读 | `KnowledgeRetrievalService` / `KnowledgeDocumentService` | `kb:space:read` |
| `getMySchedule` / `searchAvailableCourses` / `getCourseDetails` | 只读 | `CourseScheduleService` / `CourseService` | `course:read` |
| `searchForumPosts` | 只读 | `ForumPostService` | 登录 + `ai:tool:use` |
| `getMyLeaveRequests` | 只读 | `LeaveRequestService` | `leave:submit` |
| `createCourse` / `updateCourse` / `updateCourseSchedule` | 写（需确认） | `CourseService` / `CourseScheduleService` | `course:manage_own` 或 `course:manage_all` |
| `selectCourse` / `dropCourse` | 写（需确认） | `CourseSelectionService` | `selection:select` |
| `submitLeaveRequest` / `reviewLeaveRequest` | 写（需确认） | `LeaveRequestService` | `leave:submit` / `leave:review` 或 `leave:manage_all` |
| `createForumPost` / `updateForumPost` / `deleteForumPost` / `createForumComment` | 写（需确认） | `ForumPostService` / `ForumCommentService` | `forum:post:write`（或 `forum:moderate`）/ `forum:comment:write` |

#### 工具访问矩阵

| 接口组 | ADMIN | TEACHER | STUDENT | 无角色用户 |
|---|:-:|:-:|:-:|:-:|
| 工具目录、调用、确认/拒绝、记录 | ✓ | ✓ | ✓ | ✗（无 `ai:tool:use`） |
| 建课/改课/排课工具 | ✓ | ✓（本人主讲课程） | ✗ | ✗ |
| 选课/退课工具 | ✓ | ✗（无 `selection:select`） | ✓ | ✗ |
| 请假提交工具 | ✓ | ✗（无 `leave:submit`） | ✓ | ✗ |
| 请假审批工具 | ✓（全部） | 仅任教课程 | ✗ | ✗ |
| 查看他人调用记录 | ✓ | ✗ | ✗ | ✗ |

## 3. 规划接口

阶段八（系统测试与部署文档）未新增业务接口。后续如接入 Qdrant 向量检索，仅新增/替换 `KnowledgeRetrievalService` 实现，`/knowledge/search` 与 `/ai/chat` 的请求/响应结构保持不变。

## 4. AI 工具与业务接口映射

| 工具 | 复用的 Service 方法 | 需要确认 |
|---|---|---|
| searchKnowledge / listKnowledgeDocuments / getKnowledgeDocument | `KnowledgeRetrievalService` / `KnowledgeDocumentService` | 否 |
| getMySchedule / searchAvailableCourses / getCourseDetails | `CourseScheduleService` / `CourseService` | 否 |
| searchForumPosts | `ForumPostService` | 否 |
| getMyLeaveRequests | `LeaveRequestService` | 否 |
| createForumPost / updateForumPost / createForumComment / deleteForumPost | `ForumPostService` / `ForumCommentService` | 是 |
| selectCourse / dropCourse | `CourseSelectionService` | 是 |
| submitLeaveRequest / reviewLeaveRequest | `LeaveRequestService` | 是 |
| createCourse / updateCourse / updateCourseSchedule | `CourseService` / `CourseScheduleService` | 是 |
| findFreeClassrooms / getMyGrades | `ClassroomService` / `GradeService` | 否 |


## 5. 校务服务接口（V10）

统一前缀 `/api/v1`，响应仍为 `ApiResponse`。权限点见 `docs/14-grades-leave-attendance.md` 第 3 节。

### 5.1 成绩与绩点

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/grades/me` | 本人已发布成绩单：总 GPA、加权均分、按学期分组 |
| GET | `/grades/rules` | 绩点换算规则 |
| GET | `/grades/course/{courseId}` | 课程成绩册（任课教师/管理员） |
| PUT | `/grades/course/{courseId}` | 批量保存草稿（`items: [{studentId, score, remark}]`） |
| POST | `/grades/course/{courseId}/publish` | 发布；要求全部选课学生已录分数 |

### 5.2 评教

`GET /evaluations/tasks`、`POST /evaluations`、`GET /evaluations/course/{courseId}`、`GET /evaluations/me`。
同一学生对同一课程同一教师只能提交一次，重复返回 409。

### 5.3 校长信箱（投诉与建议）

`POST /feedbacks`、`GET /feedbacks/me`（全员 `feedback:submit`）、`GET /feedbacks`（**仅 ADMIN 角色 + `feedback:handle`**）、
`GET /feedbacks/{id}`（本人或系统管理员）、`POST /feedbacks/{id}/reply`（仅 ADMIN，状态 + 回复）。
V15 起权限点归入 `mailbox` 模块；处理反馈在 `@PreAuthorize` 与 Service 双重校验 ADMIN 角色，
即使将来把 `feedback:handle` 分给其他角色，非管理员也无法访问处理接口。

### 5.4 空教室

`GET /classrooms/free?date&startPeriod&endPeriod&minCapacity&building&roomType`、
`GET /classrooms/week?from&startPeriod&endPeriod...`。
占用取「已发布 `sch_assignment`」与「`course_schedule`」的并集；不传日期取今天，不传节次取当前节次。

### 5.5 请假（V10 扩展）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/leave-requests/me` | 本人请假（含审批流水、销假状态） |
| GET | `/leave-requests/to-review` | 当前环节轮到我审批的申请 |
| GET | `/leave-requests/all` | `leave:read_all`：学生会/管理员全部，教师任教课程与所带班级 |
| GET | `/leave-requests/{id}` | 详情（申请人、教师、导员、学生会、管理员） |
| POST | `/leave-requests/{id}/approve` / `reject` | 逐级审批；未到最后一级时状态仍为 PENDING |
| POST | `/leave-requests/{id}/cancel` | 申请人撤销（仅 PENDING） |
| POST | `/leave-requests/{id}/revoke` | 销假（仅 APPROVED 且未销假） |
| GET | `/leave-requests/counselors` | 班级导员列表（`leave:manage_all`） |
| PUT | `/leave-requests/counselors/{classId}` | 配置导员（传空数组清空） |

### 5.6 班级考勤

`GET /attendance/classes`、`GET /attendance/classes/{classId}/timetable`、
`GET /attendance/classes/{classId}/stats`、`GET /attendance/sessions`、
`POST /attendance/sessions`、`GET /attendance/sessions/{id}`、
`PUT /attendance/sessions/{id}`、`POST /attendance/sessions/{id}/finalize`。

### 5.7 设置

`GET /settings`、`PUT /settings/{key}`（`system:config`）、`GET /settings/leave-flow`（所有登录用户）。

### 5.8 AI 工具补充

| 工具 | 复用 | 需要确认 |
|---|---|---|
| findFreeClassrooms | `ClassroomService.free` | 否 |
| getMyGrades | `GradeService.transcript` | 否 |


## 6. 通知、考勤申诉与教室借用接口（V11）

### 6.1 通知

`GET /notifications`（`unreadOnly` + 分页）、`GET /notifications/unread-count`、
`POST /notifications/{id}/read`、`POST /notifications/read-all`、
`GET /notifications/deliveries`（`system:config`，查看邮件/微信投递状态）。

### 6.2 考勤申诉

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/attendance/me` | 本人考勤明细（含申诉状态、是否可申诉） |
| POST | `/attendance/appeals` | 提交申诉（`recordId/reason/targetStatus`） |
| GET | `/attendance/appeals/me` | 我的申诉 |
| GET | `/attendance/appeals` | 待我复核（按权限与数据归属过滤） |
| POST | `/attendance/appeals/{id}/decide` | 通过/驳回；通过时改考勤状态 |
| POST | `/attendance/sessions/{id}/sync-roster` | 把新入班学生补进旧场次 |

### 6.3 教室借用

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/room-bookings` | 申请借用（`room:book`） |
| GET | `/room-bookings/me` | 我的借用 |
| GET | `/room-bookings` | 待审批（`room:approve`，默认 PENDING） |
| POST | `/room-bookings/{id}/decide` | 通过/驳回；通过前再次校验冲突 |
| POST | `/room-bookings/{id}/cancel` | 取消（本人、PENDING） |

## 7. 论坛通知与公告接口（V12）

### 7.1 通知扩展

| 方法 | 路径 | 变更 |
|---|---|---|
| GET | `/notifications` | 新增 `category` 过滤（`FORUM` / `ANNOUNCEMENT` 等） |
| GET | `/notifications/popups` | 未读且 `popup_required=true` 的公告，`order by id`，默认最多 5 条 |
| POST | `/notifications/{id}/read` | 语义扩展为「已读即已确认」，关闭弹窗时调用 |

`NotificationView` 新增 `popupRequired: boolean`。

### 7.2 论坛

| 方法 | 路径 | 变更 |
|---|---|---|
| POST | `/forum/posts` | 公告分类发帖需 `forum:announce`，成功后广播 `ANNOUNCEMENT` 站内信 |
| PUT | `/forum/posts/{id}` | 目标分类或原分类为公告分类时同样校验 `forum:announce` |
| POST | `/forum/categories` | 请求体新增 `announcement?: boolean` |
| PUT | `/forum/categories/{id}` | 同上；不传则保持原值 |
| PATCH | `/forum/posts/{id}/status` | 隐藏/恢复/置顶/取消置顶会通知作者 |
| POST | `/forum/posts/{id}/comments` | 评论通知作者，回复额外通知被回复人 |
| DELETE | `/forum/posts/{id}`、`/forum/comments/{id}` | 版主删除时通知原作者 |

`ForumCategoryView` 新增 `announcement: boolean`。

## 8. 论坛限时置顶（V13）

`PATCH /forum/posts/{id}/status` 请求体扩展：

```json
{ "pinned": true, "pinnedDays": 7, "reason": "置顶一周" }
```

| 字段 | 说明 |
|---|---|
| `pinned` | 是否置顶；不传表示不改置顶，只改状态 |
| `pinnedDays` | 置顶天数：`0` 长期置顶，不传默认 7 天，上限 90（`@Max`，越界 400） |

响应体（`ForumPostSummaryView` / `ForumPostDetailView`）新增 `pinnedUntil`；
置顶过期后返回 `pinned = false, pinnedUntil = null`，列表排序也按「有效置顶」计算。

## 9. 个人资料接口（V14）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/profile` | 我的资料：学号/工号、姓名、组织、角色、头像版本、最近登录 |
| POST | `/profile/avatar` | multipart `file`，PNG/JPEG/WebP ≤1.5MB；成功后版本自增 |
| DELETE | `/profile/avatar` | 删除头像，回到姓名首字 |
| GET | `/profile/avatar/{userId}` | 头像二进制；需登录；没有头像返回 404；`Cache-Control: private, max-age=600` |
| GET | `/profile/posts` | 我发过的帖子（含被隐藏，不含已删除），分页 |

`/auth/me` 与登录响应的 `user` 增加 `avatarVersion`；改密继续用 `PUT /auth/password`（返回新令牌），
退出继续用 `POST /auth/logout`。

### 9.1 头像与作者信息（本轮补充）

论坛列表/详情/评论的响应都带上 `authorAvatarVersion`（评论还有 `replyToAvatarVersion`），
0 表示没有头像；前端据此用 `/profile/avatar/{userId}?v={version}` 拉图。

`GET /profile/posts` 的每一项增加：

| 字段 | 说明 |
|---|---|
| `createdAt` | 发布时间（列表展示「发布于 …」） |
| `editedAt` | 内容被编辑过才有值；用 `version > 0` 判断，评论/置顶不会误报 |
| `editorName` | 最近一次编辑者姓名（作者自己改就是本人） |
| `editorRole` | 编辑者主要角色编码（ADMIN > TEACHER > STUDENT_UNION > STUDENT） |

编辑与删除沿用论坛接口：`PUT /forum/posts/{id}`（作者或版主，需带 `version`）、`DELETE /forum/posts/{id}`。
