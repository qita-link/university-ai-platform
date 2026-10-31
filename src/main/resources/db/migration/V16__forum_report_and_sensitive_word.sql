-- =====================================================================
-- V16 论坛内容合规：举报、审核队列、敏感词
-- =====================================================================

-- 举报：同一个人对同一个目标只能报一次（重复提交是误触，不是新证据）
CREATE TABLE forum_report (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    target_type VARCHAR(16)  NOT NULL COMMENT 'POST/COMMENT',
    target_id   BIGINT       NOT NULL,
    reporter_id BIGINT       NOT NULL,
    reason      VARCHAR(32)  NOT NULL COMMENT 'SPAM/ABUSE/PORN/ILLEGAL/ACADEMIC/OTHER',
    detail      VARCHAR(500) NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ACCEPTED/REJECTED',
    handled_by  BIGINT       NULL,
    handled_at  DATETIME(3)  NULL,
    handle_note VARCHAR(500) NULL,
    created_by  BIGINT       NULL,
    updated_by  BIGINT       NULL,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_forum_report_once (target_type, target_id, reporter_id),
    KEY idx_forum_report_status (status, created_at),
    KEY idx_forum_report_target (target_type, target_id),
    CONSTRAINT fk_forum_report_user FOREIGN KEY (reporter_id) REFERENCES sys_user (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '论坛举报';

-- 敏感词：发帖/评论/改帖时命中即拒绝，避免违规内容落库
CREATE TABLE forum_sensitive_word (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    word       VARCHAR(64) NOT NULL,
    category   VARCHAR(32) NULL COMMENT 'ACADEMIC/ILLEGAL/SPAM/ABUSE',
    enabled    BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_forum_sensitive_word (word),
    KEY idx_forum_sensitive_word_enabled (enabled)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '论坛敏感词';

-- 初始词库：可在「管理 → 设置」或直接改表维护。这里只放少量示例，正式上线前按学校要求补充。
INSERT INTO forum_sensitive_word (word, category) VALUES
    ('代考', 'ACADEMIC'),
    ('代写论文', 'ACADEMIC'),
    ('办证', 'ILLEGAL'),
    ('博彩', 'ILLEGAL'),
    ('刷单', 'SPAM');
