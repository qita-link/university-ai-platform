-- =====================================================================
-- V13: 论坛限时置顶
-- 约定与 V1~V12 一致：InnoDB / utf8mb4 / DATETIME(3) / BIGINT 自增主键。
-- 设计要点：
--   1) pinned 仍是「是否置顶」的开关，pinned_until 记录到期时间；
--      NULL 表示长期置顶（历史数据与「永久」选项），到期后按未置顶参与排序。
--   2) 不做定时任务清理：排序与展示都按「pinned 且未到期」计算，
--      过期数据下次被版主编辑时会自然清掉，避免引入额外调度。
-- =====================================================================

ALTER TABLE forum_post
    ADD COLUMN pinned_until DATETIME(3) NULL COMMENT '置顶到期时间；NULL 表示长期置顶'
        AFTER pinned;

-- 列表排序会按「置顶且未到期」先排，补一个索引避免全表排序
ALTER TABLE forum_post
    ADD KEY idx_forum_post_pin (pinned, pinned_until, last_activity_at);
