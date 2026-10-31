-- =====================================================================
-- V14: 个人资料与头像
-- 约定与 V1~V13 一致：InnoDB / utf8mb4 / DATETIME(3) / BIGINT 自增主键。
-- 设计要点：
--   1) 头像二进制单独一张表，避免每次查用户都把图片读出来；
--   2) sys_user.avatar_version 在上传/删除后自增，前端用它拼接 URL 做缓存失效，
--      不依赖文件系统时间戳；
--   3) 头像接口需要登录，其余用户也能看（同一组织内互相认识），但不允许匿名枚举。
-- =====================================================================

ALTER TABLE sys_user
    ADD COLUMN avatar_version INT NOT NULL DEFAULT 0 COMMENT '头像版本：上传/删除后自增，用于缓存失效'
        AFTER email;

CREATE TABLE user_avatar (
    user_id      BIGINT      NOT NULL,
    content_type VARCHAR(32) NOT NULL COMMENT 'image/png、image/jpeg、image/webp',
    size_bytes   INT         NOT NULL,
    bytes        MEDIUMBLOB  NOT NULL,
    updated_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id),
    CONSTRAINT fk_user_avatar_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户头像';
