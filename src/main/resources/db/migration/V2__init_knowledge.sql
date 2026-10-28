-- =====================================================================
-- V2: 知识库模块
-- 约定与 V1 一致：InnoDB / utf8mb4 / DATETIME(3) / BIGINT 自增主键。
-- 文档正文与块保存到数据库；chunk 使用 MySQL ngram 全文索引，供阶段四检索。
-- 组织范围通过 kb_space.scope_org_unit_id 表达，权限由应用层 DataScopeService 计算。
-- =====================================================================

-- 知识库分类：树形，最多两级（应用层校验），用于前端目录体验。
CREATE TABLE kb_category (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    parent_id   BIGINT       NULL COMMENT '上级分类，顶级为空',
    name        VARCHAR(64)  NOT NULL,
    description VARCHAR(500) NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    -- 生成列用于让顶级分类也受 (parent_id, name) 唯一约束保护（NULL 不参与唯一比较）
    parent_key  BIGINT       GENERATED ALWAYS AS (IFNULL(parent_id, 0)) STORED,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_category_parent_name (parent_key, name),
    KEY idx_kb_category_parent (parent_id),
    CONSTRAINT fk_kb_category_parent FOREIGN KEY (parent_id) REFERENCES kb_category (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '知识库分类';

-- 知识库空间：可见范围 PUBLIC/ORG/PRIVATE；COURSE 预留给阶段六课程模块。
CREATE TABLE kb_space (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    category_id        BIGINT        NULL,
    name               VARCHAR(128)  NOT NULL,
    description        VARCHAR(1000) NULL,
    visibility         VARCHAR(16)   NOT NULL COMMENT 'PUBLIC/ORG/PRIVATE/COURSE',
    scope_org_unit_id  BIGINT        NULL COMMENT 'visibility=ORG 时的组织范围',
    scope_course_id    BIGINT        NULL COMMENT 'visibility=COURSE 时预留，阶段六接课程表',
    owner_user_id      BIGINT        NOT NULL,
    status             VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/ARCHIVED/DELETED',
    version            INT           NOT NULL DEFAULT 0 COMMENT '乐观锁',
    created_by         BIGINT        NULL,
    updated_by         BIGINT        NULL,
    created_at         DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_kb_space_category (category_id),
    KEY idx_kb_space_owner (owner_user_id),
    KEY idx_kb_space_visibility (visibility, status),
    KEY idx_kb_space_scope_org (scope_org_unit_id),
    CONSTRAINT fk_kb_space_category FOREIGN KEY (category_id) REFERENCES kb_category (id) ON DELETE RESTRICT,
    CONSTRAINT fk_kb_space_owner FOREIGN KEY (owner_user_id) REFERENCES sys_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_kb_space_scope_org FOREIGN KEY (scope_org_unit_id) REFERENCES sys_org_unit (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '知识库空间';

-- 空间成员：MANAGER/EDITOR/VIEWER。空间 owner 也会写入一行 MANAGER，便于统一查询。
CREATE TABLE kb_space_member (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    space_id    BIGINT      NOT NULL,
    user_id     BIGINT      NOT NULL,
    member_role VARCHAR(16) NOT NULL COMMENT 'MANAGER/EDITOR/VIEWER',
    granted_by  BIGINT      NULL,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_space_member (space_id, user_id),
    KEY idx_kb_space_member_user (user_id),
    CONSTRAINT fk_kb_space_member_space FOREIGN KEY (space_id) REFERENCES kb_space (id) ON DELETE CASCADE,
    CONSTRAINT fk_kb_space_member_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '知识库成员授权';

-- 批量上传批次：逐文件处理，允许部分成功。
CREATE TABLE kb_upload_batch (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    space_id      BIGINT      NOT NULL,
    uploader_id   BIGINT      NOT NULL,
    total_count   INT         NOT NULL DEFAULT 0,
    success_count INT         NOT NULL DEFAULT 0,
    failed_count  INT         NOT NULL DEFAULT 0,
    status        VARCHAR(16) NOT NULL DEFAULT 'PROCESSING' COMMENT 'PROCESSING/COMPLETED/PARTIAL/FAILED',
    created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_kb_upload_batch_space (space_id, created_at),
    CONSTRAINT fk_kb_upload_batch_space FOREIGN KEY (space_id) REFERENCES kb_space (id) ON DELETE RESTRICT,
    CONSTRAINT fk_kb_upload_batch_uploader FOREIGN KEY (uploader_id) REFERENCES sys_user (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '知识库批量上传批次';

-- 文档：软删除；source_path 用于本地语料导入的幂等识别（普通上传为空）。
CREATE TABLE kb_document (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    space_id           BIGINT       NOT NULL,
    batch_id           BIGINT       NULL,
    title              VARCHAR(255) NOT NULL,
    original_filename  VARCHAR(255) NULL,
    source_type        VARCHAR(16)  NOT NULL DEFAULT 'UPLOAD' COMMENT 'UPLOAD/EDITOR/IMPORT',
    source_path        VARCHAR(500) NULL COMMENT '导入来源相对路径，用于幂等',
    current_version_id BIGINT       NULL,
    status             VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DELETED',
    process_status     VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/INDEXED/FAILED',
    process_error      VARCHAR(1000) NULL,
    content_hash       CHAR(64)     NULL,
    effective_from     DATETIME(3)  NULL,
    effective_to       DATETIME(3)  NULL,
    uploaded_by        BIGINT       NOT NULL,
    deleted_at         DATETIME(3)  NULL,
    deleted_by         BIGINT       NULL,
    version            INT          NOT NULL DEFAULT 0 COMMENT '乐观锁',
    created_by         BIGINT       NULL,
    updated_by         BIGINT       NULL,
    created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_document_source (space_id, source_path),
    KEY idx_kb_document_space (space_id, status, id),
    KEY idx_kb_document_process (process_status),
    KEY idx_kb_document_batch (batch_id),
    CONSTRAINT fk_kb_document_space FOREIGN KEY (space_id) REFERENCES kb_space (id) ON DELETE RESTRICT,
    CONSTRAINT fk_kb_document_batch FOREIGN KEY (batch_id) REFERENCES kb_upload_batch (id) ON DELETE SET NULL,
    CONSTRAINT fk_kb_document_uploader FOREIGN KEY (uploaded_by) REFERENCES sys_user (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '知识库文档';

-- 文档版本：每次编辑/重新导入生成新版本，原文进入 content。
CREATE TABLE kb_document_version (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    document_id  BIGINT       NOT NULL,
    version_no   INT          NOT NULL,
    content      MEDIUMTEXT   NOT NULL COMMENT 'Markdown 原文',
    content_hash CHAR(64)     NOT NULL,
    size_bytes   INT          NOT NULL,
    change_note  VARCHAR(255) NULL,
    created_by   BIGINT       NULL,
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_document_version (document_id, version_no),
    CONSTRAINT fk_kb_document_version_doc FOREIGN KEY (document_id) REFERENCES kb_document (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '知识库文档版本';

-- 文档块：只对 ACTIVE 状态的当前版本块做检索；旧版本块标记 STALE。
CREATE TABLE kb_chunk (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    document_id      BIGINT       NOT NULL,
    version_id       BIGINT       NOT NULL,
    space_id         BIGINT       NOT NULL COMMENT '冗余，用于检索时按空间权限过滤',
    chunk_index      INT          NOT NULL,
    heading_path     VARCHAR(500) NULL COMMENT '如：第二章 > 2.1 奖学金',
    content          MEDIUMTEXT   NOT NULL,
    char_count       INT          NOT NULL,
    token_estimate   INT          NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/STALE',
    embedding_status VARCHAR(16)  NOT NULL DEFAULT 'NONE' COMMENT 'NONE/PENDING/DONE/FAILED',
    vector_id        VARCHAR(64)  NULL COMMENT 'Qdrant 点 ID，阶段四使用',
    created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_chunk_version_index (version_id, chunk_index),
    KEY idx_kb_chunk_document (document_id, status),
    KEY idx_kb_chunk_space (space_id, status),
    FULLTEXT KEY ft_kb_chunk_content (heading_path, content) WITH PARSER ngram,
    CONSTRAINT fk_kb_chunk_document FOREIGN KEY (document_id) REFERENCES kb_document (id) ON DELETE CASCADE,
    CONSTRAINT fk_kb_chunk_version FOREIGN KEY (version_id) REFERENCES kb_document_version (id) ON DELETE CASCADE,
    CONSTRAINT fk_kb_chunk_space FOREIGN KEY (space_id) REFERENCES kb_space (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '知识库文档块';
