-- =====================================================================
-- V15: 校长信箱独立成模块（服务台改为教室、课程改为课表后的权限归位）
-- 约定与 V1~V14 一致：只追加，不改已发布的迁移脚本。
-- 设计要点：
--   1) 投诉与建议全员可见：任何角色都具备 feedback:submit（含后续新建角色，
--      这里做一次补齐，新角色仍由管理员在角色页或直接授权）；
--   2) 处理反馈是系统管理员专属：先清理非 ADMIN 角色上的 feedback:handle，
--      业务层再用「ADMIN 角色 + feedback:handle 权限点」双重校验；
--   3) 权限点 module 归到 mailbox，管理端「角色与权限」里按新模块名展示。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 权限点改名与归类
-- ---------------------------------------------------------------------
UPDATE sys_permission SET name = '校长信箱：提交投诉与建议', module = 'mailbox'
WHERE code = 'feedback:submit';

UPDATE sys_permission SET name = '校长信箱：处理投诉与建议（仅系统管理员）', module = 'mailbox'
WHERE code = 'feedback:handle';

-- ---------------------------------------------------------------------
-- 2. 全员可提交：补齐所有现有角色的 feedback:submit
-- ---------------------------------------------------------------------
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
         JOIN sys_permission p ON p.code = 'feedback:submit'
         LEFT JOIN sys_role_permission rp ON rp.role_id = r.id AND rp.permission_id = p.id
WHERE rp.role_id IS NULL;

-- ---------------------------------------------------------------------
-- 3. 处理反馈仅保留在 ADMIN 角色上
-- ---------------------------------------------------------------------
DELETE rp
FROM sys_role_permission rp
         JOIN sys_role r ON r.id = rp.role_id
         JOIN sys_permission p ON p.id = rp.permission_id
WHERE p.code = 'feedback:handle'
  AND r.code <> 'ADMIN';
