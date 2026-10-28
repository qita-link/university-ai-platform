-- =====================================================================
-- 初始化数据库与应用账号（用有建库/建用户权限的账号执行，例如 root）
--   mysql -h 127.0.0.1 -P 3306 -u root -p < scripts/init-db.sql
-- 按需修改库名、账号与密码；生产环境请使用随机强密码。
-- =====================================================================

CREATE DATABASE IF NOT EXISTS campus_ai
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

-- 应用账号：只授予该库的 DML + Flyway 需要的 DDL，不给 SUPER/FILE/GRANT
CREATE USER IF NOT EXISTS 'campus'@'%' IDENTIFIED BY 'change-me-strong-password';
ALTER USER 'campus'@'%' IDENTIFIED BY 'change-me-strong-password';

GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES, DROP
    ON campus_ai.* TO 'campus'@'%';
FLUSH PRIVILEGES;

-- 校验：知识库/论坛的中文检索依赖 InnoDB ngram 解析器（默认 2）
SELECT @@version AS mysql_version, @@ngram_token_size AS ngram_token_size;
SELECT user, host FROM mysql.user WHERE user = 'campus';
SHOW GRANTS FOR 'campus'@'%';
