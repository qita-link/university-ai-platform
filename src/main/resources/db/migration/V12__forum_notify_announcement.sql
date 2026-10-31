-- =====================================================================
-- V12: 论坛通知接入与官方公告弹窗
-- 约定与 V1~V11 一致：InnoDB / utf8mb4 / DATETIME(3) / BIGINT 自增主键。
-- 设计要点：
--   1) 论坛的评论、回复、版主操作都写站内信，复用 notification 表与顶栏红点；
--   2) 论坛分类可标记为「公告分类」：在其中发帖需要 forum:announce 权限，
--      发布后一次性广播全员站内信，并带上 popup_required 让前端弹窗提示；
--   3) 广播用一条 INSERT ... SELECT 完成，避免逐用户开事务；
--      弹窗只在「未读 + 需要弹窗」时出现，关闭即视为已读，不会反复打扰。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 论坛分类：公告标记
-- ---------------------------------------------------------------------
ALTER TABLE forum_category
    ADD COLUMN announcement BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否为公告分类：发帖需 forum:announce，发布后广播全员'
        AFTER status;

-- ---------------------------------------------------------------------
-- 2. 通知：弹窗标记
-- ---------------------------------------------------------------------
ALTER TABLE notification
    ADD COLUMN popup_required BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否需要在登录后弹窗提示（官方公告）'
        AFTER biz_id;

ALTER TABLE notification
    ADD KEY idx_notification_popup (user_id, popup_required, read_at, id);

-- ---------------------------------------------------------------------
-- 3. 权限点：发布官方公告
-- ---------------------------------------------------------------------
INSERT INTO sys_permission (code, name, module) VALUES
    ('forum:announce', '在公告分类发布官方公告', 'forum');

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p
  ON p.code IN ('forum:announce')
WHERE r.code = 'ADMIN';

-- ---------------------------------------------------------------------
-- 4. 设置项：论坛通知与公告弹窗
-- ---------------------------------------------------------------------
INSERT INTO platform_setting (setting_key, setting_value, description) VALUES
    ('forum.notify.comment',   'true', '帖子有新评论时通知作者'),
    ('forum.notify.reply',     'true', '评论被回复时通知被回复人'),
    ('forum.notify.moderate',  'true', '帖子/评论被版主隐藏、恢复或删除时通知作者'),
    ('announcement.popup',     'true', '官方公告发布后向全员弹窗提示（同时写入站内信）');

-- ---------------------------------------------------------------------
-- 5. 内置一个公告分类（已存在同名或已有公告分类时跳过）
-- ---------------------------------------------------------------------
INSERT INTO forum_category (name, description, sort_order, status, announcement)
SELECT '通知公告', '学校与各部门的官方通知、公告；由管理员发布', 0, 'ACTIVE', TRUE
 WHERE NOT EXISTS (SELECT 1 FROM forum_category WHERE announcement = TRUE)
   AND NOT EXISTS (SELECT 1 FROM forum_category WHERE name = '通知公告');
