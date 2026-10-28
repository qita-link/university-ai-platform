-- =====================================================================
-- V5: 课程、排课、选课与请假
-- 约定与 V1~V4 一致：InnoDB / utf8mb4 / DATETIME(3) / BIGINT 自增主键。
-- 课程软删除用 status=CANCELLED（停开）表达，不物理删除，保留选课与请假追溯；
-- 排课、授课教师属于课程的从属数据，随课程级联删除（课程本身不会被物理删除）。
-- 选课容量用 course.selected_count 冗余计数 + 条件更新保证不超卖；
-- course_selection 用生成列 active_key 保证“同一课程同一学生只有一条 SELECTED”，
-- 同时允许退课（DROPPED，active_key 为 NULL）后重新选课。
-- =====================================================================

-- 学期：课程归属学期；选课窗口独立于学期起止日期（通常在开学前后）。
CREATE TABLE semester (
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    code               VARCHAR(32) NOT NULL COMMENT '学期编码，如 2026-2027-1',
    name               VARCHAR(64) NOT NULL,
    start_date         DATE        NOT NULL,
    end_date           DATE        NOT NULL,
    selection_start_at DATETIME(3) NOT NULL COMMENT '选课开始时间（含）',
    selection_end_at   DATETIME(3) NOT NULL COMMENT '选课结束时间（含）',
    status             VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/ACTIVE/CLOSED',
    created_by         BIGINT      NULL,
    updated_by         BIGINT      NULL,
    created_at         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_semester_code (code),
    KEY idx_semester_status_range (status, start_date, end_date),
    CONSTRAINT ck_semester_date_range CHECK (start_date <= end_date),
    CONSTRAINT ck_semester_selection_range CHECK (selection_start_at <= selection_end_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '学期';

-- 课程：同一学期内课程编码唯一；selected_count 由选课服务条件更新维护。
-- CHECK(selected_count <= capacity) 是数据库层兜底，缩容前服务层必须先校验。
CREATE TABLE course (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    semester_id    BIGINT        NOT NULL,
    code           VARCHAR(32)   NOT NULL COMMENT '课程编码，学期内唯一',
    name           VARCHAR(128)  NOT NULL,
    credit         DECIMAL(3, 1) NOT NULL DEFAULT 0.0 COMMENT '学分，0~20，保留一位小数',
    description    VARCHAR(1000) NULL,
    org_unit_id    BIGINT        NULL COMMENT '开课组织',
    capacity       INT           NOT NULL DEFAULT 0 COMMENT '容量',
    selected_count INT           NOT NULL DEFAULT 0 COMMENT '已选人数（冗余计数）',
    status         VARCHAR(16)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/OPEN/CLOSED/CANCELLED',
    version        INT           NOT NULL DEFAULT 0 COMMENT '乐观锁：只跟踪课程资料修改',
    created_by     BIGINT        NULL,
    updated_by     BIGINT        NULL,
    created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_course_semester_code (semester_id, code),
    KEY idx_course_semester_status (semester_id, status, id),
    KEY idx_course_org (org_unit_id),
    CONSTRAINT fk_course_semester FOREIGN KEY (semester_id) REFERENCES semester (id) ON DELETE RESTRICT,
    CONSTRAINT fk_course_org FOREIGN KEY (org_unit_id) REFERENCES sys_org_unit (id) ON DELETE RESTRICT,
    CONSTRAINT ck_course_selected_count CHECK (selected_count >= 0 AND selected_count <= capacity)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '课程';

-- 授课教师：一门课程必须有且仅有一名 PRIMARY 主讲，可有多名 ASSISTANT。
CREATE TABLE course_teacher (
    course_id    BIGINT      NOT NULL,
    teacher_id   BIGINT      NOT NULL,
    teacher_role VARCHAR(16) NOT NULL DEFAULT 'PRIMARY' COMMENT 'PRIMARY/ASSISTANT',
    created_by   BIGINT      NULL,
    updated_by   BIGINT      NULL,
    created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (course_id, teacher_id),
    KEY idx_course_teacher_teacher (teacher_id, course_id),
    CONSTRAINT fk_course_teacher_course FOREIGN KEY (course_id) REFERENCES course (id) ON DELETE CASCADE,
    CONSTRAINT fk_course_teacher_user FOREIGN KEY (teacher_id) REFERENCES sys_user (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '课程授课教师';

-- 排课：星期 1~7，节次 1~12，周次 1~30，单双周由 week_parity 表达。
-- 时间冲突检测在应用层按 (星期, 节次区间, 周次区间, 单双周) 计算，索引用于按课程/教师取排课。
CREATE TABLE course_schedule (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    course_id    BIGINT      NOT NULL,
    day_of_week  TINYINT     NOT NULL COMMENT '1=周一 … 7=周日',
    start_period TINYINT     NOT NULL COMMENT '开始节次',
    end_period   TINYINT     NOT NULL COMMENT '结束节次',
    start_week   TINYINT     NOT NULL COMMENT '开始周',
    end_week     TINYINT     NOT NULL COMMENT '结束周',
    week_parity  VARCHAR(8)  NOT NULL DEFAULT 'ALL' COMMENT 'ALL/ODD/EVEN',
    classroom    VARCHAR(64) NULL,
    created_by   BIGINT      NULL,
    updated_by   BIGINT      NULL,
    created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_course_schedule_course (course_id, day_of_week, start_period),
    CONSTRAINT fk_course_schedule_course FOREIGN KEY (course_id) REFERENCES course (id) ON DELETE CASCADE,
    CONSTRAINT ck_course_schedule_day CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT ck_course_schedule_period CHECK (start_period BETWEEN 1 AND 12 AND end_period BETWEEN start_period AND 12),
    CONSTRAINT ck_course_schedule_week CHECK (start_week BETWEEN 1 AND 30 AND end_week BETWEEN start_week AND 30)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '课程排课';

-- 选课：active_key 为生成列，SELECTED 时为 1，其余为 NULL，
-- 配合唯一键 (course_id, student_id, active_key) 实现“有效选课唯一 + 退课后可重选”。
CREATE TABLE course_selection (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    course_id   BIGINT      NOT NULL,
    student_id  BIGINT      NOT NULL,
    status      VARCHAR(16) NOT NULL DEFAULT 'SELECTED' COMMENT 'SELECTED/DROPPED',
    active_key  TINYINT     GENERATED ALWAYS AS (IF(status = 'SELECTED', 1, NULL)) STORED
        COMMENT '有效选课标记，用于唯一约束',
    selected_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    dropped_at  DATETIME(3) NULL,
    created_by  BIGINT      NULL,
    updated_by  BIGINT      NULL,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_course_selection_active (course_id, student_id, active_key),
    KEY idx_course_selection_student (student_id, status, course_id),
    KEY idx_course_selection_course (course_id, status),
    CONSTRAINT fk_course_selection_course FOREIGN KEY (course_id) REFERENCES course (id) ON DELETE RESTRICT,
    CONSTRAINT fk_course_selection_student FOREIGN KEY (student_id) REFERENCES sys_user (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '选课记录';

-- 请假：schedule_id 可空（按天请假不对应具体节次）；排课被删除时置空，不连带删除请假。
-- 审批使用 WHERE status = 'PENDING' 的条件更新，version 记录审批/撤销引起的状态变更。
CREATE TABLE leave_request (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    student_id     BIGINT       NOT NULL,
    course_id      BIGINT       NOT NULL,
    schedule_id    BIGINT       NULL,
    start_at       DATETIME(3)  NOT NULL,
    end_at         DATETIME(3)  NOT NULL,
    reason         VARCHAR(500) NOT NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED/CANCELLED',
    reviewer_id    BIGINT       NULL,
    review_comment VARCHAR(500) NULL,
    reviewed_at    DATETIME(3)  NULL,
    version        INT          NOT NULL DEFAULT 0 COMMENT '乐观锁：审批/撤销递增',
    created_by     BIGINT       NULL,
    updated_by     BIGINT       NULL,
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_leave_request_student (student_id, created_at),
    KEY idx_leave_request_course_status (course_id, status, id),
    KEY idx_leave_request_status (status, id),
    CONSTRAINT fk_leave_request_student FOREIGN KEY (student_id) REFERENCES sys_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_leave_request_course FOREIGN KEY (course_id) REFERENCES course (id) ON DELETE RESTRICT,
    CONSTRAINT fk_leave_request_schedule FOREIGN KEY (schedule_id) REFERENCES course_schedule (id) ON DELETE SET NULL,
    CONSTRAINT fk_leave_request_reviewer FOREIGN KEY (reviewer_id) REFERENCES sys_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_leave_request_time CHECK (start_at < end_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '请假申请';

-- 知识库 COURSE 可见范围：V2 预留的 scope_course_id 在此接入课程表外键。
-- 课程只停开不物理删除，因此 RESTRICT 不会阻碍业务。
ALTER TABLE kb_space
    ADD CONSTRAINT fk_kb_space_course FOREIGN KEY (scope_course_id) REFERENCES course (id) ON DELETE RESTRICT;
