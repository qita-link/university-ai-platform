-- =====================================================================
-- V8: 自动排课（scheduling）
-- 约定与 V1~V7 一致：InnoDB / utf8mb4 / DATETIME(3) / BIGINT 自增主键。
-- 复用：semester（学期）、sys_user（教师）、sys_org_unit type=CLASS（行政班）。
-- 课程库 sch_course 与阶段六的选课课程 course 分开：前者是课程基本信息，后者是学期内开课 + 选课。
-- 节次/星期/周次统一使用整数：day_of_week 1..7，period 1..16，week 1..30；
-- 周次集合在应用层用位图表达（第 w 周 = bit w-1）。
-- 详见 docs/07-scheduling.md
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 学期排课配置与教学日历
-- ---------------------------------------------------------------------
CREATE TABLE sch_semester_config (
    semester_id      BIGINT       NOT NULL,
    teaching_weeks   TINYINT      NOT NULL COMMENT '教学周数 1..30',
    first_week_start DATE         NOT NULL COMMENT '第 1 周的周一',
    teaching_days    VARCHAR(32)  NOT NULL DEFAULT '1,2,3,4,5' COMMENT '每周教学日，逗号分隔 1..7',
    periods_per_day  TINYINT      NOT NULL DEFAULT 12 COMMENT '每天节数 1..16',
    default_travel_minutes INT    NOT NULL DEFAULT 30 COMMENT '未配置校区通行时间时，不同校区之间的默认通行分钟数',
    max_room_candidates    INT    NOT NULL DEFAULT 12 COMMENT '每个任务保留的候选教室数上限',
    created_by       BIGINT       NULL,
    updated_by       BIGINT       NULL,
    created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (semester_id),
    CONSTRAINT fk_sch_cfg_semester FOREIGN KEY (semester_id) REFERENCES semester (id) ON DELETE CASCADE,
    CONSTRAINT ck_sch_cfg_weeks CHECK (teaching_weeks BETWEEN 1 AND 30),
    CONSTRAINT ck_sch_cfg_periods CHECK (periods_per_day BETWEEN 1 AND 16),
    CONSTRAINT ck_sch_cfg_travel CHECK (default_travel_minutes BETWEEN 0 AND 600),
    CONSTRAINT ck_sch_cfg_rooms CHECK (max_room_candidates BETWEEN 1 AND 200)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '学期排课配置';

-- 节次作息：section 相同且编号连续的节次才能连堂（午休/晚饭时间自然隔开）。
CREATE TABLE sch_period (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    semester_id BIGINT      NOT NULL,
    period_no   TINYINT     NOT NULL COMMENT '节次 1..16',
    start_time  TIME        NOT NULL,
    end_time    TIME        NOT NULL,
    section     VARCHAR(16) NOT NULL COMMENT 'MORNING/AFTERNOON/EVENING',
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sch_period (semester_id, period_no),
    CONSTRAINT fk_sch_period_semester FOREIGN KEY (semester_id) REFERENCES semester (id) ON DELETE CASCADE,
    CONSTRAINT ck_sch_period_no CHECK (period_no BETWEEN 1 AND 16),
    CONSTRAINT ck_sch_period_time CHECK (start_time < end_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '每日节次作息';

-- 特殊教学周：teaching_allowed = FALSE 的周（考试周/实践周/假期周）不排理论课。
CREATE TABLE sch_calendar_week (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    semester_id      BIGINT       NOT NULL,
    week_no          TINYINT      NOT NULL,
    week_type        VARCHAR(16)  NOT NULL COMMENT 'EXAM/PRACTICE/HOLIDAY/OTHER',
    teaching_allowed BOOLEAN      NOT NULL DEFAULT FALSE,
    note             VARCHAR(200) NULL,
    created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sch_calendar_week (semester_id, week_no),
    CONSTRAINT fk_sch_week_semester FOREIGN KEY (semester_id) REFERENCES semester (id) ON DELETE CASCADE,
    CONSTRAINT ck_sch_week_no CHECK (week_no BETWEEN 1 AND 30)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '特殊教学周';

-- 特殊日期：
--   HOLIDAY   法定节假日，当天不上课；
--   SUSPENDED 临时停课；
--   WORKDAY   调休上课日（通常是周末），按 follow_day_of_week 那一天的课表上课；
--   MAKEUP    补课日，用于记录 follow_date 那天停掉的课在本日补上（follow_date 必填）。
CREATE TABLE sch_calendar_date (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    semester_id        BIGINT       NOT NULL,
    event_date         DATE         NOT NULL,
    date_type          VARCHAR(16)  NOT NULL COMMENT 'HOLIDAY/SUSPENDED/WORKDAY/MAKEUP',
    follow_day_of_week TINYINT      NULL COMMENT 'WORKDAY：按星期几的课表上课',
    follow_date        DATE         NULL COMMENT 'MAKEUP：补的是哪一天的课',
    name               VARCHAR(64)  NOT NULL,
    created_by         BIGINT       NULL,
    updated_by         BIGINT       NULL,
    created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sch_calendar_date (semester_id, event_date),
    CONSTRAINT fk_sch_date_semester FOREIGN KEY (semester_id) REFERENCES semester (id) ON DELETE CASCADE,
    CONSTRAINT ck_sch_date_follow CHECK (follow_day_of_week IS NULL OR follow_day_of_week BETWEEN 1 AND 7)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '教学日历特殊日期';

-- ---------------------------------------------------------------------
-- 2. 校区、教室
-- ---------------------------------------------------------------------
CREATE TABLE sch_campus (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    code       VARCHAR(32) NOT NULL,
    name       VARCHAR(64) NOT NULL,
    status     VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
    created_by BIGINT      NULL,
    updated_by BIGINT      NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sch_campus_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '校区';

-- 校区通行时间：按无序对存储（from_campus_id < to_campus_id），应用层保证。
CREATE TABLE sch_campus_travel (
    from_campus_id BIGINT      NOT NULL,
    to_campus_id   BIGINT      NOT NULL,
    minutes        INT         NOT NULL,
    created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (from_campus_id, to_campus_id),
    CONSTRAINT fk_sch_travel_from FOREIGN KEY (from_campus_id) REFERENCES sch_campus (id) ON DELETE CASCADE,
    CONSTRAINT fk_sch_travel_to FOREIGN KEY (to_campus_id) REFERENCES sch_campus (id) ON DELETE CASCADE,
    -- MySQL 不允许在带级联动作的外键列上建 CHECK，因此 from < to 由应用层保证
    CONSTRAINT ck_sch_travel_minutes CHECK (minutes BETWEEN 0 AND 600)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '校区间通行时间';

CREATE TABLE sch_room (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    code       VARCHAR(32)  NOT NULL COMMENT '教室编号，全局唯一',
    name       VARCHAR(64)  NOT NULL,
    campus_id  BIGINT       NOT NULL,
    building   VARCHAR(64)  NULL,
    floor_no   INT          NULL,
    capacity   INT          NOT NULL,
    room_type  VARCHAR(32)  NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/MULTIMEDIA/COMPUTER_LAB/LANGUAGE_LAB/LAB/GYM/OTHER',
    features   VARCHAR(255) NULL COMMENT '设备标签，逗号分隔，如 PROJECTOR,COMPUTER',
    status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
    note       VARCHAR(200) NULL,
    version    INT          NOT NULL DEFAULT 0,
    created_by BIGINT       NULL,
    updated_by BIGINT       NULL,
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sch_room_code (code),
    KEY idx_sch_room_campus (campus_id, status),
    CONSTRAINT fk_sch_room_campus FOREIGN KEY (campus_id) REFERENCES sch_campus (id) ON DELETE RESTRICT,
    CONSTRAINT ck_sch_room_capacity CHECK (capacity BETWEEN 1 AND 5000)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '教室与教学场地';

-- ---------------------------------------------------------------------
-- 3. 教师与行政班的排课档案（扩展已有 sys_user / sys_org_unit，不重复建实体）
-- ---------------------------------------------------------------------
-- 上限字段为空表示不限制。
CREATE TABLE sch_teacher_profile (
    teacher_id              BIGINT      NOT NULL,
    org_unit_id             BIGINT      NULL COMMENT '所属学院/系（排课口径，可与账号组织不同）',
    title                   VARCHAR(32) NULL COMMENT '职称',
    home_campus_id          BIGINT      NULL,
    max_weekly_periods      INT         NULL,
    max_daily_periods       INT         NULL,
    max_daily_sessions      INT         NULL COMMENT '每天最多课次数',
    max_consecutive_periods INT         NULL,
    note                    VARCHAR(200) NULL,
    version                 INT         NOT NULL DEFAULT 0,
    created_by              BIGINT      NULL,
    updated_by              BIGINT      NULL,
    created_at              DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at              DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (teacher_id),
    KEY idx_sch_teacher_org (org_unit_id),
    CONSTRAINT fk_sch_tp_user FOREIGN KEY (teacher_id) REFERENCES sys_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_sch_tp_org FOREIGN KEY (org_unit_id) REFERENCES sys_org_unit (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sch_tp_campus FOREIGN KEY (home_campus_id) REFERENCES sch_campus (id) ON DELETE SET NULL,
    CONSTRAINT ck_sch_tp_limits CHECK ((max_weekly_periods IS NULL OR max_weekly_periods BETWEEN 1 AND 112)
        AND (max_daily_periods IS NULL OR max_daily_periods BETWEEN 1 AND 16)
        AND (max_daily_sessions IS NULL OR max_daily_sessions BETWEEN 1 AND 16)
        AND (max_consecutive_periods IS NULL OR max_consecutive_periods BETWEEN 1 AND 16))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '教师排课档案';

CREATE TABLE sch_class_profile (
    org_unit_id             BIGINT      NOT NULL COMMENT '行政班（sys_org_unit.type=CLASS）',
    student_count           INT         NOT NULL,
    grade                   VARCHAR(16) NULL COMMENT '年级，如 2024',
    home_campus_id          BIGINT      NULL,
    max_daily_periods       INT         NULL,
    max_consecutive_periods INT         NULL,
    version                 INT         NOT NULL DEFAULT 0,
    created_by              BIGINT      NULL,
    updated_by              BIGINT      NULL,
    created_at              DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at              DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (org_unit_id),
    CONSTRAINT fk_sch_cp_org FOREIGN KEY (org_unit_id) REFERENCES sys_org_unit (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sch_cp_campus FOREIGN KEY (home_campus_id) REFERENCES sch_campus (id) ON DELETE SET NULL,
    CONSTRAINT ck_sch_cp_count CHECK (student_count BETWEEN 0 AND 5000),
    CONSTRAINT ck_sch_cp_limits CHECK ((max_daily_periods IS NULL OR max_daily_periods BETWEEN 1 AND 16)
        AND (max_consecutive_periods IS NULL OR max_consecutive_periods BETWEEN 1 AND 16))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '行政班排课档案';

-- ---------------------------------------------------------------------
-- 4. 课程库、教师可授课程
-- ---------------------------------------------------------------------
CREATE TABLE sch_course (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    code              VARCHAR(32)   NOT NULL,
    name              VARCHAR(128)  NOT NULL,
    course_type       VARCHAR(16)   NOT NULL DEFAULT 'THEORY' COMMENT 'THEORY/LAB/PRACTICE/MIXED',
    credit            DECIMAL(4, 1) NOT NULL DEFAULT 0.0,
    total_hours       INT           NOT NULL COMMENT '总学时（理论 + 实验）',
    org_unit_id       BIGINT        NULL COMMENT '开课单位',
    default_room_type VARCHAR(32)   NULL,
    required_features VARCHAR(255)  NULL,
    priority          TINYINT       NOT NULL DEFAULT 5 COMMENT '1..10，越大越优先',
    status            VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
    description       VARCHAR(500)  NULL,
    version           INT           NOT NULL DEFAULT 0,
    created_by        BIGINT        NULL,
    updated_by        BIGINT        NULL,
    created_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sch_course_code (code),
    KEY idx_sch_course_org (org_unit_id),
    CONSTRAINT fk_sch_course_org FOREIGN KEY (org_unit_id) REFERENCES sys_org_unit (id) ON DELETE RESTRICT,
    CONSTRAINT ck_sch_course_hours CHECK (total_hours BETWEEN 1 AND 2000),
    CONSTRAINT ck_sch_course_priority CHECK (priority BETWEEN 1 AND 10)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '课程库（课程基本信息）';

CREATE TABLE sch_teacher_course (
    teacher_id BIGINT      NOT NULL,
    course_id  BIGINT      NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (teacher_id, course_id),
    KEY idx_sch_tc_course (course_id),
    CONSTRAINT fk_sch_tc_teacher FOREIGN KEY (teacher_id) REFERENCES sys_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_sch_tc_course FOREIGN KEY (course_id) REFERENCES sch_course (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '教师可授课程';

-- ---------------------------------------------------------------------
-- 5. 教学班（实际上课的学生集合）
-- ---------------------------------------------------------------------
-- 成员 full_class = TRUE：整个行政班参加（与该班其它整班课程冲突）；
-- full_class = FALSE：只有该班部分学生（选修抽取），student_count 必填；
-- 选修抽取成员之间允许同时段，但仍与该班整班课程冲突。
CREATE TABLE sch_teaching_class (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    semester_id   BIGINT       NOT NULL,
    code          VARCHAR(48)  NOT NULL,
    name          VARCHAR(128) NOT NULL,
    student_count INT          NULL COMMENT '为空时按成员人数求和',
    created_by    BIGINT       NULL,
    updated_by    BIGINT       NULL,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sch_tclass_code (semester_id, code),
    CONSTRAINT fk_sch_tclass_semester FOREIGN KEY (semester_id) REFERENCES semester (id) ON DELETE RESTRICT,
    CONSTRAINT ck_sch_tclass_count CHECK (student_count IS NULL OR student_count BETWEEN 0 AND 5000)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '教学班';

CREATE TABLE sch_teaching_class_member (
    teaching_class_id BIGINT      NOT NULL,
    org_unit_id       BIGINT      NOT NULL COMMENT '行政班',
    full_class        BOOLEAN     NOT NULL DEFAULT TRUE,
    student_count     INT         NULL COMMENT 'full_class=FALSE 时必填',
    created_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (teaching_class_id, org_unit_id),
    KEY idx_sch_tcm_org (org_unit_id),
    CONSTRAINT fk_sch_tcm_tclass FOREIGN KEY (teaching_class_id) REFERENCES sch_teaching_class (id) ON DELETE CASCADE,
    CONSTRAINT fk_sch_tcm_org FOREIGN KEY (org_unit_id) REFERENCES sys_org_unit (id) ON DELETE RESTRICT,
    CONSTRAINT ck_sch_tcm_count CHECK (full_class OR student_count IS NOT NULL)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '教学班成员（行政班）';

-- ---------------------------------------------------------------------
-- 6. 教学任务：某学期某课程由某些教师面向某教学班开设
-- ---------------------------------------------------------------------
-- 学时一致性：total_hours = periods_per_session × sessions_per_week × 有效教学周数，由预检校验。
CREATE TABLE sch_task (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    semester_id         BIGINT       NOT NULL,
    code                VARCHAR(48)  NOT NULL COMMENT '任务编号，学期内唯一',
    course_id           BIGINT       NOT NULL,
    teaching_class_id   BIGINT       NOT NULL,
    org_unit_id         BIGINT       NULL COMMENT '开课单位（为空取课程开课单位），用于按学院划分排课范围',
    start_week          TINYINT      NOT NULL,
    end_week            TINYINT      NOT NULL,
    week_parity         VARCHAR(8)   NOT NULL DEFAULT 'ALL' COMMENT 'ALL/ODD/EVEN',
    sessions_per_week   TINYINT      NOT NULL COMMENT '每周上课次数',
    periods_per_session TINYINT      NOT NULL COMMENT '单次课连续节数（连堂节数）',
    total_hours         INT          NOT NULL COMMENT '本任务总学时',
    room_required       BOOLEAN      NOT NULL DEFAULT TRUE,
    room_type           VARCHAR(32)  NULL COMMENT '为空取课程默认教室类型',
    required_features   VARCHAR(255) NULL COMMENT '为空取课程设备要求',
    priority            TINYINT      NULL COMMENT '为空取课程优先级',
    allow_same_day      BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '是否允许同一天上两次',
    uniform_start       BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '每次课是否必须同一起始节',
    linked_course_id    BIGINT       NULL COMMENT '可选：对应阶段六选课课程 course.id',
    status              VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
    note                VARCHAR(200) NULL,
    version             INT          NOT NULL DEFAULT 0,
    created_by          BIGINT       NULL,
    updated_by          BIGINT       NULL,
    created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sch_task_code (semester_id, code),
    KEY idx_sch_task_semester (semester_id, status),
    KEY idx_sch_task_course (course_id),
    KEY idx_sch_task_tclass (teaching_class_id),
    CONSTRAINT fk_sch_task_semester FOREIGN KEY (semester_id) REFERENCES semester (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sch_task_course FOREIGN KEY (course_id) REFERENCES sch_course (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sch_task_tclass FOREIGN KEY (teaching_class_id) REFERENCES sch_teaching_class (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sch_task_org FOREIGN KEY (org_unit_id) REFERENCES sys_org_unit (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sch_task_linked FOREIGN KEY (linked_course_id) REFERENCES course (id) ON DELETE RESTRICT,
    CONSTRAINT ck_sch_task_week CHECK (start_week BETWEEN 1 AND 30 AND end_week BETWEEN start_week AND 30),
    CONSTRAINT ck_sch_task_sessions CHECK (sessions_per_week BETWEEN 1 AND 14),
    CONSTRAINT ck_sch_task_len CHECK (periods_per_session BETWEEN 1 AND 8),
    CONSTRAINT ck_sch_task_hours CHECK (total_hours BETWEEN 1 AND 2000),
    CONSTRAINT ck_sch_task_priority CHECK (priority IS NULL OR priority BETWEEN 1 AND 10)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '教学任务';

-- 共同授课：每次课全部教师同时在场，均计入各自工作量与冲突。
CREATE TABLE sch_task_teacher (
    task_id      BIGINT      NOT NULL,
    teacher_id   BIGINT      NOT NULL,
    teacher_role VARCHAR(16) NOT NULL DEFAULT 'MAIN' COMMENT 'MAIN/CO',
    created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (task_id, teacher_id),
    KEY idx_sch_tt_teacher (teacher_id),
    CONSTRAINT fk_sch_tt_task FOREIGN KEY (task_id) REFERENCES sch_task (id) ON DELETE CASCADE,
    CONSTRAINT fk_sch_tt_teacher FOREIGN KEY (teacher_id) REFERENCES sys_user (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '教学任务授课教师';

-- 教室指定/偏好/禁用：REQUIRED 存在时只能用 REQUIRED 教室。
CREATE TABLE sch_task_room (
    task_id    BIGINT      NOT NULL,
    room_id    BIGINT      NOT NULL,
    mode       VARCHAR(16) NOT NULL COMMENT 'REQUIRED/PREFERRED/FORBIDDEN',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (task_id, room_id),
    CONSTRAINT fk_sch_tr_task FOREIGN KEY (task_id) REFERENCES sch_task (id) ON DELETE CASCADE,
    CONSTRAINT fk_sch_tr_room FOREIGN KEY (room_id) REFERENCES sch_room (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '教学任务教室要求';

-- ---------------------------------------------------------------------
-- 7. 时间规则与约束配置
-- ---------------------------------------------------------------------
-- 统一表达各类对象的时间条件：
--   target_type: GLOBAL/TEACHER/CLASS/TEACHING_CLASS/ROOM/COURSE/TASK；GLOBAL 时 target_id 为空
--   rule_kind  : FORBIDDEN（硬，禁排/不可用/维护）、FIXED（硬，固定时段，仅 COURSE/TASK）、
--                AVOID（软，尽量不排）、PREFERRED（软，尽量排在此）
--   day_of_week 为空 = 每个教学日；start_week/end_week 为空 = 整个学期
-- weight 是该规则在所属软约束中的倍数（1..10），对硬规则无意义。
CREATE TABLE sch_time_rule (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    semester_id  BIGINT       NOT NULL,
    target_type  VARCHAR(16)  NOT NULL,
    target_id    BIGINT       NULL,
    rule_kind    VARCHAR(16)  NOT NULL,
    day_of_week  TINYINT      NULL,
    start_period TINYINT      NOT NULL,
    end_period   TINYINT      NOT NULL,
    start_week   TINYINT      NULL,
    end_week     TINYINT      NULL,
    weight       TINYINT      NOT NULL DEFAULT 1,
    note         VARCHAR(200) NULL,
    created_by   BIGINT       NULL,
    updated_by   BIGINT       NULL,
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_sch_rule_target (semester_id, target_type, target_id),
    CONSTRAINT fk_sch_rule_semester FOREIGN KEY (semester_id) REFERENCES semester (id) ON DELETE CASCADE,
    CONSTRAINT ck_sch_rule_day CHECK (day_of_week IS NULL OR day_of_week BETWEEN 1 AND 7),
    CONSTRAINT ck_sch_rule_period CHECK (start_period BETWEEN 1 AND 16 AND end_period BETWEEN start_period AND 16),
    CONSTRAINT ck_sch_rule_week CHECK ((start_week IS NULL AND end_week IS NULL)
        OR (start_week BETWEEN 1 AND 30 AND end_week BETWEEN start_week AND 30)),
    CONSTRAINT ck_sch_rule_weight CHECK (weight BETWEEN 1 AND 10)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '排课时间规则';

-- 软约束按学期覆盖默认权重；硬约束不可关闭，因此只保存软约束。
CREATE TABLE sch_constraint_config (
    semester_id BIGINT      NOT NULL,
    code        VARCHAR(32) NOT NULL,
    enabled     BOOLEAN     NOT NULL DEFAULT TRUE,
    weight      INT         NOT NULL,
    updated_by  BIGINT      NULL,
    updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (semester_id, code),
    CONSTRAINT fk_sch_cc_semester FOREIGN KEY (semester_id) REFERENCES semester (id) ON DELETE CASCADE,
    CONSTRAINT ck_sch_cc_weight CHECK (weight BETWEEN 0 AND 1000)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '软约束权重配置';

-- ---------------------------------------------------------------------
-- 8. 排课任务（一次求解）、版本、课时安排
-- ---------------------------------------------------------------------
CREATE TABLE sch_version (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    semester_id       BIGINT        NOT NULL,
    version_no        INT           NOT NULL COMMENT '学期内递增',
    name              VARCHAR(128)  NOT NULL,
    source            VARCHAR(16)   NOT NULL COMMENT 'SOLVER/COPY',
    job_id            BIGINT        NULL,
    base_version_id   BIGINT        NULL,
    status            VARCHAR(16)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PUBLISHED/ARCHIVED',
    published_key     TINYINT       GENERATED ALWAYS AS (IF(status = 'PUBLISHED', 1, NULL)) STORED
        COMMENT '每学期最多一个已发布版本',
    total_sessions    INT           NOT NULL DEFAULT 0 COMMENT '应排课次（每周）',
    placed_sessions   INT           NOT NULL DEFAULT 0,
    hard_violations   INT           NOT NULL DEFAULT 0,
    score             DECIMAL(6, 2) NULL COMMENT '0..100',
    soft_penalty      BIGINT        NULL,
    evaluation_json   MEDIUMTEXT    NULL COMMENT '最近一次复核与评分结果',
    note              VARCHAR(500)  NULL,
    published_at      DATETIME(3)   NULL,
    published_by      BIGINT        NULL,
    version           INT           NOT NULL DEFAULT 0 COMMENT '乐观锁：任何调整/状态变更递增',
    created_by        BIGINT        NULL,
    updated_by        BIGINT        NULL,
    created_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sch_version_no (semester_id, version_no),
    UNIQUE KEY uk_sch_version_published (semester_id, published_key),
    KEY idx_sch_version_status (semester_id, status),
    CONSTRAINT fk_sch_version_semester FOREIGN KEY (semester_id) REFERENCES semester (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sch_version_base FOREIGN KEY (base_version_id) REFERENCES sch_version (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '排课版本';

CREATE TABLE sch_job (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    semester_id       BIGINT       NOT NULL,
    name              VARCHAR(128) NOT NULL,
    mode              VARCHAR(16)  NOT NULL DEFAULT 'FULL' COMMENT 'FULL 全量/INCREMENTAL 以基准版本为起点局部重排',
    base_version_id   BIGINT       NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED',
    params_json       TEXT         NOT NULL COMMENT '求解参数、范围、权重覆盖',
    cancel_requested  BOOLEAN      NOT NULL DEFAULT FALSE,
    progress          TINYINT      NOT NULL DEFAULT 0 COMMENT '0..100',
    phase             VARCHAR(32)  NULL COMMENT 'LOADING/BUILDING/SOLVING/SAVING',
    solver_status     VARCHAR(32)  NULL COMMENT 'CP-SAT 结果：OPTIMAL/FEASIBLE/INFEASIBLE/UNKNOWN/MODEL_INVALID',
    objective         BIGINT       NULL,
    best_bound        BIGINT       NULL,
    stats_json        TEXT         NULL COMMENT '变量数、候选数、约束数等',
    result_version_id BIGINT       NULL,
    error_code        VARCHAR(64)  NULL,
    message           VARCHAR(1000) NULL,
    log_text          MEDIUMTEXT   NULL COMMENT '求解日志（截断）',
    started_at        DATETIME(3)  NULL,
    finished_at       DATETIME(3)  NULL,
    version           INT          NOT NULL DEFAULT 0,
    created_by        BIGINT       NULL,
    updated_by        BIGINT       NULL,
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_sch_job_semester (semester_id, created_at),
    KEY idx_sch_job_status (status),
    CONSTRAINT fk_sch_job_semester FOREIGN KEY (semester_id) REFERENCES semester (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sch_job_base FOREIGN KEY (base_version_id) REFERENCES sch_version (id) ON DELETE SET NULL,
    CONSTRAINT fk_sch_job_result FOREIGN KEY (result_version_id) REFERENCES sch_version (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '排课任务（一次求解）';

ALTER TABLE sch_version
    ADD CONSTRAINT fk_sch_version_job FOREIGN KEY (job_id) REFERENCES sch_job (id) ON DELETE SET NULL;

-- 课时安排：教学任务第 session_index 次课（每周重复，周次范围取任务定义）。
-- day_of_week 为空表示该课次未安排（保留行以便人工放置并追踪原因）。
CREATE TABLE sch_assignment (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    version_id    BIGINT      NOT NULL,
    task_id       BIGINT      NOT NULL,
    session_index TINYINT     NOT NULL COMMENT '1..sessions_per_week',
    day_of_week   TINYINT     NULL,
    start_period  TINYINT     NULL,
    end_period    TINYINT     NULL,
    room_id       BIGINT      NULL,
    locked        BOOLEAN     NOT NULL DEFAULT FALSE,
    origin        VARCHAR(16) NOT NULL DEFAULT 'SOLVER' COMMENT 'SOLVER/MANUAL/LOCKED/COPY',
    created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sch_assignment_session (version_id, task_id, session_index),
    KEY idx_sch_assignment_slot (version_id, day_of_week, start_period),
    KEY idx_sch_assignment_room (version_id, room_id),
    KEY idx_sch_assignment_task (task_id),
    CONSTRAINT fk_sch_assignment_version FOREIGN KEY (version_id) REFERENCES sch_version (id) ON DELETE CASCADE,
    CONSTRAINT fk_sch_assignment_task FOREIGN KEY (task_id) REFERENCES sch_task (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sch_assignment_room FOREIGN KEY (room_id) REFERENCES sch_room (id) ON DELETE RESTRICT,
    CONSTRAINT ck_sch_assignment_slot CHECK ((day_of_week IS NULL AND start_period IS NULL AND end_period IS NULL)
        OR (day_of_week BETWEEN 1 AND 7 AND start_period BETWEEN 1 AND 16 AND end_period BETWEEN start_period AND 16))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '课时安排';

-- 版本问题：未安排原因、硬约束违反、预检警告等（每次复核整体重写）。
CREATE TABLE sch_version_issue (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    version_id  BIGINT        NOT NULL,
    severity    VARCHAR(16)   NOT NULL COMMENT 'ERROR/WARNING/INFO',
    code        VARCHAR(48)   NOT NULL,
    task_id     BIGINT        NULL,
    assignment_id BIGINT      NULL,
    message     VARCHAR(1000) NOT NULL,
    created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_sch_issue_version (version_id, severity),
    CONSTRAINT fk_sch_issue_version FOREIGN KEY (version_id) REFERENCES sch_version (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '排课版本问题清单';

CREATE TABLE sch_adjustment_log (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    version_id    BIGINT       NOT NULL,
    assignment_id BIGINT       NOT NULL,
    action        VARCHAR(16)  NOT NULL COMMENT 'MOVE/UNPLACE/LOCK/UNLOCK/SWAP',
    from_day      TINYINT      NULL,
    from_period   TINYINT      NULL,
    from_room_id  BIGINT       NULL,
    to_day        TINYINT      NULL,
    to_period     TINYINT      NULL,
    to_room_id    BIGINT       NULL,
    reason        VARCHAR(500) NULL,
    created_by    BIGINT       NULL,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_sch_adjust_version (version_id, created_at),
    CONSTRAINT fk_sch_adjust_version FOREIGN KEY (version_id) REFERENCES sch_version (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '课表人工调整记录';

-- ---------------------------------------------------------------------
-- 9. 权限点与教务员角色
-- ---------------------------------------------------------------------
INSERT INTO sys_role (code, name, description, built_in) VALUES
    ('ACADEMIC_ADMIN', '教务员', '维护排课基础数据、执行自动排课、调整与发布课表', TRUE);

INSERT INTO sys_permission (code, name, module) VALUES
    ('schedule:read',            '查看排课基础数据、任务、版本与全部课表', 'scheduling'),
    ('schedule:manage',          '维护排课基础数据、执行排课与调整课表',   'scheduling'),
    ('schedule:publish',         '发布/撤销发布课表',                     'scheduling'),
    ('schedule:preference:self', '维护本人的排课时间偏好',                 'scheduling'),
    ('timetable:read',           '查看本人已发布课表',                     'scheduling');

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p
  ON p.code IN ('schedule:read', 'schedule:manage', 'schedule:publish', 'schedule:preference:self', 'timetable:read')
WHERE r.code = 'ADMIN';

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p
  ON p.code IN ('schedule:read', 'schedule:manage', 'schedule:publish', 'timetable:read', 'course:read')
WHERE r.code = 'ACADEMIC_ADMIN';

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p
  ON p.code IN ('schedule:preference:self', 'timetable:read')
WHERE r.code = 'TEACHER';

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p
  ON p.code IN ('timetable:read')
WHERE r.code = 'STUDENT';