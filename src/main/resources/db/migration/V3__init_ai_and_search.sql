-- =====================================================================
-- V3: 知识库检索补充字段 + AI 问答记录
-- 阶段四：为 chunk 增加文档标题（标题加权检索），并保存对话/检索/引用/Token。
-- =====================================================================

ALTER TABLE kb_chunk
    ADD COLUMN document_title VARCHAR(255) NULL COMMENT '文档标题冗余，用于标题加权' AFTER space_id;

UPDATE kb_chunk c
    JOIN kb_document d ON d.id = c.document_id
SET c.document_title = d.title
WHERE c.document_title IS NULL;

ALTER TABLE kb_chunk
    ADD FULLTEXT KEY ft_kb_chunk_title (document_title) WITH PARSER ngram;

CREATE TABLE ai_conversation (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    title           VARCHAR(255) NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DELETED',
    last_message_at DATETIME(3)  NULL,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_ai_conversation_user (user_id, status, last_message_at),
    CONSTRAINT fk_ai_conversation_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI 对话';

CREATE TABLE ai_message (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    conversation_id  BIGINT       NOT NULL,
    role             VARCHAR(16)  NOT NULL COMMENT 'USER/ASSISTANT',
    content          MEDIUMTEXT   NOT NULL,
    model            VARCHAR(128) NULL,
    prompt_tokens    INT          NULL,
    completion_tokens INT         NULL,
    token_source     VARCHAR(16)  NOT NULL DEFAULT 'ESTIMATED' COMMENT 'REAL/ESTIMATED',
    latency_ms       INT          NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'SUCCESS' COMMENT 'SUCCESS/FAILED/NO_EVIDENCE',
    error_code       VARCHAR(64)  NULL,
    created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_ai_message_conversation (conversation_id, id),
    KEY idx_ai_message_status (status),
    CONSTRAINT fk_ai_message_conversation FOREIGN KEY (conversation_id) REFERENCES ai_conversation (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI 对话消息';

CREATE TABLE ai_retrieval_record (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    message_id      BIGINT       NOT NULL COMMENT '对应的助手消息',
    query_text      VARCHAR(1000) NOT NULL,
    rewritten_query VARCHAR(1000) NULL,
    strategy        VARCHAR(16)  NOT NULL COMMENT 'KEYWORD/VECTOR/HYBRID',
    candidate_count INT          NOT NULL DEFAULT 0,
    selected_count  INT          NOT NULL DEFAULT 0,
    context_tokens  INT          NOT NULL DEFAULT 0,
    latency_ms      INT          NULL,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_ai_retrieval_message (message_id),
    CONSTRAINT fk_ai_retrieval_message FOREIGN KEY (message_id) REFERENCES ai_message (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI 检索记录';

CREATE TABLE ai_message_citation (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    message_id       BIGINT       NOT NULL,
    chunk_id         BIGINT       NULL COMMENT '引用块；块可能后来变 STALE，不设外键以保留追溯',
    document_id      BIGINT       NOT NULL,
    version_id       BIGINT       NOT NULL,
    rank_no          INT          NOT NULL,
    score            DOUBLE       NULL,
    used_in_context  BOOLEAN      NOT NULL DEFAULT TRUE,
    snippet          VARCHAR(1000) NULL,
    created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_ai_citation_message (message_id, rank_no),
    CONSTRAINT fk_ai_citation_message FOREIGN KEY (message_id) REFERENCES ai_message (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI 回答引用';
