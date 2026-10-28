-- =====================================================================
-- V1: 系统基础表（组织、用户、角色、权限、审计）
-- 约定：InnoDB / utf8mb4 / 主键 BIGINT 自增 / 时间 DATETIME(3)（业务时区 Asia/Shanghai）
-- 状态使用 VARCHAR 存储枚举名，由应用层枚举约束，便于扩展且可读。
-- 后续阶段的业务表由 V2+ 迁移脚本按模块追加，详见 docs/03-database-design.md
-- =====================================================================

-- 组织单元：学院 / 系 / 专业 / 班级，用于数据权限隔离
CREATE TABLE sys_org_unit (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    parent_id   BIGINT       NULL COMMENT '上级组织，顶级为空',
    code        VARCHAR(64)  NOT NULL COMMENT '组织编码，全局唯一',
    name        VARCHAR(128) NOT NULL,
    type        VARCHAR(16)  NOT NULL COMMENT 'COLLEGE/DEPARTMENT/MAJOR/CLASS',
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
    sort_order  INT          NOT NULL DEFAULT 0,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_org_unit_code (code),
    KEY idx_org_unit_parent (parent_id),
    CONSTRAINT fk_org_unit_parent FOREIGN KEY (parent_id) REFERENCES sys_org_unit (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '组织单元';

-- 用户：学号/工号作为登录名；不物理删除，通过 status 停用
CREATE TABLE sys_user (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    username            VARCHAR(64)  NOT NULL COMMENT '登录名（学号/工号）',
    password_hash       VARCHAR(100) NOT NULL COMMENT 'BCrypt 等自适应哈希，带算法前缀',
    display_name        VARCHAR(64)  NOT NULL,
    email               VARCHAR(128) NULL,
    phone               VARCHAR(32)  NULL,
    org_unit_id         BIGINT       NULL COMMENT '所属组织（学院/班级）',
    status              VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED/LOCKED',
    token_version       INT          NOT NULL DEFAULT 0 COMMENT '自增即令已签发 Token 失效（改密/停用/强制下线）',
    failed_login_count  INT          NOT NULL DEFAULT 0,
    locked_until        DATETIME(3)  NULL COMMENT '连续登录失败临时锁定截止时间',
    last_login_at       DATETIME(3)  NULL,
    password_changed_at DATETIME(3)  NULL,
    version             INT          NOT NULL DEFAULT 0 COMMENT '乐观锁',
    created_by          BIGINT       NULL,
    updated_by          BIGINT       NULL,
    created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_username (username),
    UNIQUE KEY uk_user_email (email),
    KEY idx_user_org_unit (org_unit_id),
    KEY idx_user_status (status),
    CONSTRAINT fk_user_org_unit FOREIGN KEY (org_unit_id) REFERENCES sys_org_unit (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户';

CREATE TABLE sys_role (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    code        VARCHAR(32)  NOT NULL COMMENT 'ADMIN/TEACHER/STUDENT/...',
    name        VARCHAR(64)  NOT NULL,
    description VARCHAR(255) NULL,
    built_in    BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '内置角色不可删除',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '角色';

-- 权限点：功能能力（能否做某类操作）。数据归属（能否操作某条数据）由业务服务校验。
CREATE TABLE sys_permission (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    code        VARCHAR(64)  NOT NULL COMMENT '形如 module:resource:action',
    name        VARCHAR(64)  NOT NULL,
    module      VARCHAR(32)  NOT NULL,
    description VARCHAR(255) NULL,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_permission_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '权限点';

CREATE TABLE sys_user_role (
    user_id    BIGINT      NOT NULL,
    role_id    BIGINT      NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id, role_id),
    KEY idx_user_role_role (role_id),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES sys_role (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户-角色';

CREATE TABLE sys_role_permission (
    role_id       BIGINT      NOT NULL,
    permission_id BIGINT      NOT NULL,
    created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (role_id, permission_id),
    KEY idx_role_perm_perm (permission_id),
    CONSTRAINT fk_role_perm_role FOREIGN KEY (role_id) REFERENCES sys_role (id) ON DELETE CASCADE,
    CONSTRAINT fk_role_perm_perm FOREIGN KEY (permission_id) REFERENCES sys_permission (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '角色-权限';

-- 审计日志：只追加，不更新、不删除；不设外键，保证即使关联数据变化审计仍可写入
CREATE TABLE audit_log (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    trace_id       VARCHAR(64)   NULL COMMENT '请求追踪 ID，与应用日志关联',
    actor_user_id  BIGINT        NULL COMMENT '操作人，系统任务为空',
    actor_username VARCHAR(64)   NULL COMMENT '操作人用户名快照',
    source         VARCHAR(16)   NOT NULL COMMENT 'API/AI_TOOL/SYSTEM',
    module         VARCHAR(32)   NOT NULL,
    action         VARCHAR(64)   NOT NULL COMMENT '如 KB_DOCUMENT_DELETE',
    target_type    VARCHAR(32)   NULL,
    target_id      VARCHAR(64)   NULL,
    result         VARCHAR(16)   NOT NULL COMMENT 'SUCCESS/FAILURE/DENIED',
    error_code     VARCHAR(64)   NULL,
    detail         VARCHAR(2000) NULL COMMENT '已脱敏的参数/变更摘要',
    client_ip      VARCHAR(64)   NULL,
    user_agent     VARCHAR(255)  NULL,
    created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_audit_actor_time (actor_user_id, created_at),
    KEY idx_audit_module_action_time (module, action, created_at),
    KEY idx_audit_target (target_type, target_id),
    KEY idx_audit_created_at (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '操作审计日志';

-- ---------------------------------------------------------------------
-- 基础数据：内置角色与权限点（不含任何用户账号/密码；初始管理员由启动引导创建）
-- ---------------------------------------------------------------------
INSERT INTO sys_role (code, name, description, built_in) VALUES
    ('ADMIN',   '管理员', '平台管理，拥有全局管理权限', TRUE),
    ('TEACHER', '教师',   '管理本人负责的课程、获授权的知识库，审批请假', TRUE),
    ('STUDENT', '学生',   '使用知识库问答、论坛、选课、请假', TRUE);

INSERT INTO sys_permission (code, name, module) VALUES
    ('user:read',              '查看用户',             'user'),
    ('user:manage',            '管理用户与账号状态',    'user'),
    ('role:manage',            '管理角色与权限',        'user'),
    ('org:manage',             '管理组织单元',          'user'),
    ('kb:space:read',          '访问授权知识库',        'knowledge'),
    ('kb:space:create',        '创建知识库',            'knowledge'),
    ('kb:space:manage_all',    '管理全部知识库',        'knowledge'),
    ('kb:document:write',      '在授权知识库中上传/编辑/删除文档', 'knowledge'),
    ('kb:category:manage',     '管理知识库分类',        'knowledge'),
    ('ai:chat',                '使用 AI 问答',          'ai'),
    ('ai:tool:use',            '通过 AI 调用业务工具',   'ai'),
    ('forum:post:write',       '发帖/改删本人帖子',      'forum'),
    ('forum:comment:write',    '评论/回复',             'forum'),
    ('forum:moderate',         '管理全部帖子与评论',     'forum'),
    ('forum:category:manage',  '管理论坛分类',          'forum'),
    ('course:read',            '查看课程与课表',        'course'),
    ('course:manage_own',      '管理本人负责的课程与排课', 'course'),
    ('course:manage_all',      '管理全部课程与排课',     'course'),
    ('selection:select',       '选课/退课',             'selection'),
    ('selection:manage_all',   '管理全部选课记录',       'selection'),
    ('leave:submit',           '提交/撤销本人请假',      'leave'),
    ('leave:review',           '审批有权处理的请假',     'leave'),
    ('leave:manage_all',       '管理全部请假申请',       'leave'),
    ('audit:read',             '查看审计日志与 AI 工具调用记录', 'audit'),
    ('system:config',          '管理平台基础配置',       'system');

-- ADMIN：全部权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r CROSS JOIN sys_permission p WHERE r.code = 'ADMIN';

-- TEACHER
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p
  ON p.code IN ('kb:space:read', 'kb:space:create', 'kb:document:write', 'ai:chat', 'ai:tool:use',
                'forum:post:write', 'forum:comment:write', 'course:read', 'course:manage_own', 'leave:review')
WHERE r.code = 'TEACHER';

-- STUDENT
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p
  ON p.code IN ('kb:space:read', 'ai:chat', 'ai:tool:use', 'forum:post:write', 'forum:comment:write',
                'course:read', 'selection:select', 'leave:submit')
WHERE r.code = 'STUDENT';
