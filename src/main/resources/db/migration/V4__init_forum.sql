-- =====================================================================
-- V4: 论坛模块（分类、帖子、两级评论）
-- 约定与 V1/V2/V3 一致：InnoDB / utf8mb4 / DATETIME(3) / BIGINT 自增主键。
-- 帖子与评论只做软删除（status=DELETED），保留内容用于追溯。
-- 评论最多两级：顶级评论 parent_id/root_id 为空；回复的 root_id 指向顶级评论，
-- parent_id 指向被回复的评论（可以是对回复的回复），展示时统一挂在 root 下。
-- =====================================================================

-- 论坛分类：扁平结构，管理员维护；停用后该分类下的帖子对普通用户不可见。
CREATE TABLE forum_category (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(64)  NOT NULL,
    description VARCHAR(500) NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
    created_by  BIGINT       NULL,
    updated_by  BIGINT       NULL,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_forum_category_name (name),
    KEY idx_forum_category_status_sort (status, sort_order)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '论坛分类';

-- 帖子：置顶字段参与列表排序；last_activity_at 在发帖/评论时更新。
-- FULLTEXT(title, content) 使用 ngram，支持中文关键词检索（应用层拆 2 字 ngram）。
CREATE TABLE forum_post (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    category_id      BIGINT       NOT NULL,
    author_id        BIGINT       NOT NULL,
    title            VARCHAR(200) NOT NULL,
    content          MEDIUMTEXT   NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'PUBLISHED' COMMENT 'PUBLISHED/HIDDEN/DELETED',
    pinned           BOOLEAN      NOT NULL DEFAULT FALSE,
    comment_count    INT          NOT NULL DEFAULT 0 COMMENT '未删除评论数',
    last_activity_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    deleted_at       DATETIME(3)  NULL,
    deleted_by       BIGINT       NULL,
    version          INT          NOT NULL DEFAULT 0 COMMENT '乐观锁：只跟踪帖子内容修改',
    created_by       BIGINT       NULL,
    updated_by       BIGINT       NULL,
    created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_forum_post_category (category_id, status, pinned, last_activity_at),
    KEY idx_forum_post_author (author_id, status, last_activity_at),
    KEY idx_forum_post_status_activity (status, pinned, last_activity_at),
    FULLTEXT KEY ft_forum_post_text (title, content) WITH PARSER ngram,
    CONSTRAINT fk_forum_post_category FOREIGN KEY (category_id) REFERENCES forum_category (id) ON DELETE RESTRICT,
    CONSTRAINT fk_forum_post_author FOREIGN KEY (author_id) REFERENCES sys_user (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '论坛帖子';

-- 评论：两级。顶级评论 parent_id/root_id 均为空；回复的 root_id 指向顶级评论。
-- 删除顶级评论时应用层级联软删除其全部回复；删除单条回复只影响该回复。
CREATE TABLE forum_comment (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    post_id          BIGINT        NOT NULL,
    parent_id        BIGINT        NULL COMMENT '被回复的评论；顶级评论为空',
    root_id          BIGINT        NULL COMMENT '所属顶级评论；顶级评论为空',
    author_id        BIGINT        NOT NULL,
    reply_to_user_id BIGINT        NULL COMMENT '被回复评论的作者',
    content          VARCHAR(2000) NOT NULL,
    status           VARCHAR(16)   NOT NULL DEFAULT 'PUBLISHED' COMMENT 'PUBLISHED/DELETED',
    deleted_at       DATETIME(3)   NULL,
    deleted_by       BIGINT        NULL,
    created_by       BIGINT        NULL,
    updated_by       BIGINT        NULL,
    created_at       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_forum_comment_post_root (post_id, root_id, id),
    KEY idx_forum_comment_post_parent (post_id, parent_id, id),
    KEY idx_forum_comment_author (author_id, id),
    CONSTRAINT fk_forum_comment_post FOREIGN KEY (post_id) REFERENCES forum_post (id) ON DELETE CASCADE,
    CONSTRAINT fk_forum_comment_parent FOREIGN KEY (parent_id) REFERENCES forum_comment (id) ON DELETE RESTRICT,
    CONSTRAINT fk_forum_comment_root FOREIGN KEY (root_id) REFERENCES forum_comment (id) ON DELETE RESTRICT,
    CONSTRAINT fk_forum_comment_author FOREIGN KEY (author_id) REFERENCES sys_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_forum_comment_reply_to FOREIGN KEY (reply_to_user_id) REFERENCES sys_user (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '论坛评论（两级）';
