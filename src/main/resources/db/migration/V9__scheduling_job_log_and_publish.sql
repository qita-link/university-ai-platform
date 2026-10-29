-- =====================================================================
-- V9: 排课任务日志表与课表发布记录表
-- V8 只把日志放在 sch_job.log_text（便于下载整段日志），这里补充按行追加的
-- 任务日志与发布审计，便于接口分页查询与按时间追溯；V8 的表结构不变。
-- =====================================================================

CREATE TABLE sch_job_log (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    job_id     BIGINT       NOT NULL,
    level      VARCHAR(8)   NOT NULL DEFAULT 'INFO' COMMENT 'INFO/WARN/ERROR',
    message    VARCHAR(1000) NOT NULL,
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_sch_job_log_job (job_id, id),
    CONSTRAINT fk_sch_job_log_job FOREIGN KEY (job_id) REFERENCES sch_job (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '排课任务日志';

CREATE TABLE sch_publish_record (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    semester_id  BIGINT       NOT NULL,
    version_id   BIGINT       NOT NULL,
    action       VARCHAR(16)  NOT NULL COMMENT 'PUBLISH/UNPUBLISH/ARCHIVE',
    operator_id  BIGINT       NULL,
    reason       VARCHAR(500) NULL,
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_sch_publish_semester (semester_id, created_at),
    KEY idx_sch_publish_version (version_id),
    CONSTRAINT fk_sch_publish_semester FOREIGN KEY (semester_id) REFERENCES semester (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sch_publish_version FOREIGN KEY (version_id) REFERENCES sch_version (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '课表发布记录';

-- ---------------------------------------------------------------------
-- 调整课表与执行排课的权限进一步细分：调整课表单独授权
-- ---------------------------------------------------------------------
INSERT INTO sys_permission (code, name, module) VALUES
    ('schedule:adjust', '调整课表（移动课时、锁定/解锁、局部重排）', 'scheduling');

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p ON p.code = 'schedule:adjust'
WHERE r.code IN ('ADMIN', 'ACADEMIC_ADMIN');
