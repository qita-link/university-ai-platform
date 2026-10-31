-- =====================================================================
-- V10: 成绩与绩点、评教、投诉建议、空教室、请假双审批与销假、班级考勤
-- 约定与 V1~V9 一致：InnoDB / utf8mb4 / DATETIME(3) / BIGINT 自增主键。
-- 设计要点：
--   1) 成绩先 DRAFT 后 PUBLISHED，学生只看到已发布成绩，绩点由 grade_rule 换算；
--   2) 评教对教师只暴露聚合值，不暴露学生身份；同一学生对同一教师同一课程只能评一次；
--   3) 投诉建议保留提交人用于追溯，anonymous=TRUE 时接口不返回身份；
--   4) 请假由「任课教师 + 导员」按可配置顺序审批，leave_approval 记录每一级意见；
--      销假用 revoked_at 表达，不新增终态，避免破坏 PENDING→APPROVED 状态机；
--   5) 考勤按「班级 + 课程 + 日期 + 节次」建场次，逐人登记，便于统计旷课。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 成绩与绩点
-- ---------------------------------------------------------------------
-- 绩点换算规则：全局一份，管理员可改；分数区间左闭右开，最后一段闭合到 100。
CREATE TABLE grade_rule (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    name       VARCHAR(32)  NOT NULL COMMENT '规则名，如 4.0 制',
    min_score  DECIMAL(5, 2) NOT NULL,
    max_score  DECIMAL(5, 2) NOT NULL,
    grade_point DECIMAL(3, 2) NOT NULL COMMENT '绩点',
    grade_level VARCHAR(8)   NOT NULL COMMENT '等级，如 A/B+/C',
    sort_order INT           NOT NULL DEFAULT 0,
    created_at DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_grade_rule_range (min_score, max_score),
    CONSTRAINT ck_grade_rule_range CHECK (min_score >= 0 AND max_score <= 100 AND min_score < max_score),
    CONSTRAINT ck_grade_rule_point CHECK (grade_point >= 0 AND grade_point <= 5)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '绩点换算规则';

INSERT INTO grade_rule (name, min_score, max_score, grade_point, grade_level, sort_order) VALUES
    ('4.0 制', 90, 100, 4.00, 'A',  10),
    ('4.0 制', 85, 90,  3.70, 'A-', 20),
    ('4.0 制', 82, 85,  3.30, 'B+', 30),
    ('4.0 制', 78, 82,  3.00, 'B',  40),
    ('4.0 制', 75, 78,  2.70, 'B-', 50),
    ('4.0 制', 72, 75,  2.30, 'C+', 60),
    ('4.0 制', 68, 72,  2.00, 'C',  70),
    ('4.0 制', 64, 68,  1.50, 'C-', 80),
    ('4.0 制', 60, 64,  1.00, 'D',  90),
    ('4.0 制', 0,  60,  0.00, 'F',  100);

-- 成绩：一门课程一名学生一条；教师录入为 DRAFT，发布后学生可见并计入绩点。
CREATE TABLE course_grade (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    course_id    BIGINT        NOT NULL,
    student_id   BIGINT        NOT NULL,
    score        DECIMAL(5, 2) NULL COMMENT '百分制成绩；为空表示尚未录入',
    grade_point  DECIMAL(3, 2) NULL COMMENT '按 grade_rule 换算的绩点',
    grade_level  VARCHAR(8)    NULL COMMENT '等级',
    status       VARCHAR(16)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PUBLISHED',
    remark       VARCHAR(200)  NULL COMMENT '备注，如缓考、重修',
    graded_by    BIGINT        NULL,
    published_at DATETIME(3)   NULL,
    version      INT           NOT NULL DEFAULT 0,
    created_by   BIGINT        NULL,
    updated_by   BIGINT        NULL,
    created_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_course_grade (course_id, student_id),
    KEY idx_course_grade_student (student_id, status),
    CONSTRAINT fk_course_grade_course FOREIGN KEY (course_id) REFERENCES course (id) ON DELETE CASCADE,
    CONSTRAINT fk_course_grade_student FOREIGN KEY (student_id) REFERENCES sys_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_course_grade_score CHECK (score IS NULL OR (score >= 0 AND score <= 100))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '课程成绩';

-- ---------------------------------------------------------------------
-- 2. 学生评教
-- ---------------------------------------------------------------------
-- 四个维度 1~5 分，total_score 为平均分；同一学生对同一课程同一教师只能提交一次。
CREATE TABLE teaching_evaluation (
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    semester_id         BIGINT      NOT NULL,
    course_id           BIGINT      NOT NULL,
    teacher_id          BIGINT      NOT NULL,
    student_id          BIGINT      NOT NULL,
    score_teaching      TINYINT     NOT NULL COMMENT '教学态度与方法',
    score_content       TINYINT     NOT NULL COMMENT '内容与收获',
    score_interaction   TINYINT     NOT NULL COMMENT '互动与答疑',
    score_workload      TINYINT     NOT NULL COMMENT '作业与考核合理性',
    total_score         DECIMAL(3, 2) NOT NULL COMMENT '四项平均',
    comment             VARCHAR(500) NULL,
    anonymous           BOOLEAN     NOT NULL DEFAULT TRUE,
    created_by          BIGINT       NULL,
    updated_by          BIGINT       NULL,
    created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_evaluation_once (course_id, teacher_id, student_id),
    KEY idx_evaluation_teacher (teacher_id, semester_id),
    KEY idx_evaluation_student (student_id, semester_id),
    CONSTRAINT fk_evaluation_semester FOREIGN KEY (semester_id) REFERENCES semester (id) ON DELETE RESTRICT,
    CONSTRAINT fk_evaluation_course FOREIGN KEY (course_id) REFERENCES course (id) ON DELETE CASCADE,
    CONSTRAINT fk_evaluation_teacher FOREIGN KEY (teacher_id) REFERENCES sys_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_evaluation_student FOREIGN KEY (student_id) REFERENCES sys_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_evaluation_score CHECK (score_teaching BETWEEN 1 AND 5 AND score_content BETWEEN 1 AND 5
        AND score_interaction BETWEEN 1 AND 5 AND score_workload BETWEEN 1 AND 5)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '学生评教';

-- ---------------------------------------------------------------------
-- 3. 投诉与建议
-- ---------------------------------------------------------------------
CREATE TABLE campus_feedback (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    category    VARCHAR(16)   NOT NULL COMMENT 'COMPLAINT/SUGGESTION/REPORT/OTHER',
    title       VARCHAR(120)  NOT NULL,
    content     VARCHAR(2000) NOT NULL,
    anonymous   BOOLEAN       NOT NULL DEFAULT FALSE,
    student_id  BIGINT        NOT NULL COMMENT '提交人，匿名时仅管理员可见',
    status      VARCHAR(16)   NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/PROCESSING/RESOLVED/CLOSED',
    handler_id  BIGINT        NULL,
    reply       VARCHAR(1000) NULL,
    replied_at  DATETIME(3)   NULL,
    version     INT           NOT NULL DEFAULT 0,
    created_by  BIGINT        NULL,
    updated_by  BIGINT        NULL,
    created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_feedback_status (status, id),
    KEY idx_feedback_student (student_id, id),
    CONSTRAINT fk_feedback_student FOREIGN KEY (student_id) REFERENCES sys_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_feedback_handler FOREIGN KEY (handler_id) REFERENCES sys_user (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '投诉与建议';

-- ---------------------------------------------------------------------
-- 4. 班级导员与请假双审批
-- ---------------------------------------------------------------------
-- 一个行政班可以有多名导员，任一导员均可审批本班请假。
CREATE TABLE class_counselor (
    class_id    BIGINT      NOT NULL COMMENT '行政班（sys_org_unit.type=CLASS）',
    counselor_id BIGINT     NOT NULL,
    created_by  BIGINT      NULL,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (class_id, counselor_id),
    KEY idx_class_counselor_user (counselor_id, class_id),
    CONSTRAINT fk_cc_class FOREIGN KEY (class_id) REFERENCES sys_org_unit (id) ON DELETE CASCADE,
    CONSTRAINT fk_cc_user FOREIGN KEY (counselor_id) REFERENCES sys_user (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '班级导员';

-- 请假：追加班级、审批阶段与销假字段；已有数据默认单级教师审批，保持兼容。
ALTER TABLE leave_request
    ADD COLUMN class_id       BIGINT      NULL COMMENT '提交时学生所在行政班' AFTER student_id,
    ADD COLUMN counselor_id   BIGINT      NULL COMMENT '提交时解析到的导员（可空）' AFTER class_id,
    ADD COLUMN approval_order VARCHAR(64) NOT NULL DEFAULT 'TEACHER' COMMENT '审批顺序快照，如 TEACHER,COUNSELOR',
    ADD COLUMN total_stages   TINYINT     NOT NULL DEFAULT 1 COMMENT '应审批级数快照',
    ADD COLUMN current_stage  TINYINT     NOT NULL DEFAULT 1 COMMENT '当前待审批级数',
    ADD COLUMN revoked_at     DATETIME(3) NULL COMMENT '销假时间',
    ADD COLUMN revoke_note    VARCHAR(200) NULL COMMENT '销假说明',
    ADD COLUMN revoked_by     BIGINT      NULL,
    ADD KEY idx_leave_request_class (class_id, status, id),
    ADD KEY idx_leave_request_pending_stage (status, current_stage),
    ADD CONSTRAINT fk_leave_request_class FOREIGN KEY (class_id) REFERENCES sys_org_unit (id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_leave_request_counselor FOREIGN KEY (counselor_id) REFERENCES sys_user (id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_leave_request_revoked_by FOREIGN KEY (revoked_by) REFERENCES sys_user (id) ON DELETE SET NULL;

-- 审批流水：每一级一条，唯一键 (leave_id, stage) 保证并发下同一级只有一条结论。
CREATE TABLE leave_approval (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    leave_id      BIGINT       NOT NULL,
    stage         TINYINT      NOT NULL COMMENT '第几级，从 1 开始',
    approver_role VARCHAR(16)  NOT NULL COMMENT 'TEACHER/COUNSELOR/ADMIN',
    approver_id   BIGINT       NOT NULL,
    decision      VARCHAR(16)  NOT NULL COMMENT 'APPROVED/REJECTED',
    comment       VARCHAR(500) NULL,
    decided_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_leave_approval_stage (leave_id, stage),
    KEY idx_leave_approval_approver (approver_id, decided_at),
    CONSTRAINT fk_leave_approval_leave FOREIGN KEY (leave_id) REFERENCES leave_request (id) ON DELETE CASCADE,
    CONSTRAINT fk_leave_approval_user FOREIGN KEY (approver_id) REFERENCES sys_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_leave_approval_stage CHECK (stage BETWEEN 1 AND 5)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '请假审批流水';

-- ---------------------------------------------------------------------
-- 5. 考勤（按班级、课程、日期与节次）
-- ---------------------------------------------------------------------
CREATE TABLE attendance_session (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    course_id    BIGINT      NOT NULL,
    schedule_id  BIGINT      NULL COMMENT '对应 course_schedule，手动补录时可为空',
    class_id     BIGINT      NOT NULL COMMENT '行政班；同一节课按班分别登记',
    session_date DATE        NOT NULL,
    start_period TINYINT     NOT NULL,
    end_period   TINYINT     NOT NULL,
    status       VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/FINAL',
    note         VARCHAR(200) NULL,
    version      INT         NOT NULL DEFAULT 0,
    created_by   BIGINT      NULL,
    updated_by   BIGINT      NULL,
    created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_attendance_slot (course_id, class_id, session_date, start_period),
    KEY idx_attendance_class_date (class_id, session_date),
    KEY idx_attendance_course (course_id, session_date),
    CONSTRAINT fk_att_session_course FOREIGN KEY (course_id) REFERENCES course (id) ON DELETE CASCADE,
    CONSTRAINT fk_att_session_schedule FOREIGN KEY (schedule_id) REFERENCES course_schedule (id) ON DELETE SET NULL,
    CONSTRAINT fk_att_session_class FOREIGN KEY (class_id) REFERENCES sys_org_unit (id) ON DELETE RESTRICT,
    CONSTRAINT ck_att_session_period CHECK (start_period BETWEEN 1 AND 12 AND end_period BETWEEN start_period AND 12)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '考勤场次';

CREATE TABLE attendance_record (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    session_id BIGINT       NOT NULL,
    student_id BIGINT       NOT NULL,
    status     VARCHAR(16)  NOT NULL DEFAULT 'PRESENT' COMMENT 'PRESENT/ABSENT/LATE/LEAVE',
    leave_id   BIGINT       NULL COMMENT '由已通过请假自动标记时记录',
    remark     VARCHAR(200) NULL,
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_attendance_record (session_id, student_id),
    KEY idx_attendance_record_student (student_id, status),
    CONSTRAINT fk_att_record_session FOREIGN KEY (session_id) REFERENCES attendance_session (id) ON DELETE CASCADE,
    CONSTRAINT fk_att_record_student FOREIGN KEY (student_id) REFERENCES sys_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_att_record_leave FOREIGN KEY (leave_id) REFERENCES leave_request (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '考勤明细';

-- ---------------------------------------------------------------------
-- 6. 平台设置（请假审批流程等可在管理端修改的开关）
-- ---------------------------------------------------------------------
CREATE TABLE platform_setting (
    setting_key   VARCHAR(64)  NOT NULL,
    setting_value VARCHAR(255) NOT NULL,
    description   VARCHAR(255) NULL,
    updated_by    BIGINT       NULL,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (setting_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '平台设置';

INSERT INTO platform_setting (setting_key, setting_value, description) VALUES
    ('leave.approval.teacher',   'true',             '请假是否需要任课教师审批'),
    ('leave.approval.counselor', 'true',             '请假是否需要班级导员审批'),
    ('leave.approval.order',     'TEACHER,COUNSELOR', '审批顺序，逗号分隔：TEACHER=任课教师，COUNSELOR=导员'),
    ('grade.gpa.scale',          '4.0',              '绩点制说明（仅用于展示）'),
    ('evaluation.anonymous',     'true',             '评教默认匿名');

-- ---------------------------------------------------------------------
-- 7. 权限点与角色授权
-- ---------------------------------------------------------------------
INSERT INTO sys_permission (code, name, module) VALUES
    ('grade:read',      '查看本人课程成绩与绩点', 'grade'),
    ('grade:manage',    '录入本人任教课程成绩',   'grade'),
    ('grade:manage_all','管理全部成绩与绩点规则', 'grade'),
    ('evaluation:submit','提交学生评教',          'evaluation'),
    ('evaluation:read', '查看本人课程的评教统计', 'evaluation'),
    ('feedback:submit', '提交投诉与建议',         'feedback'),
    ('feedback:handle', '处理投诉与建议',         'feedback'),
    ('classroom:read',  '查询空教室',            'classroom'),
    ('leave:read_all',  '查询全部请假情况',       'leave'),
    ('attendance:manage','登记本人课程考勤',      'attendance'),
    ('attendance:read', '查看班级课表与考勤',     'attendance');

-- ADMIN：全部新权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p
  ON p.code IN ('grade:read', 'grade:manage', 'grade:manage_all', 'evaluation:submit', 'evaluation:read',
                'feedback:submit', 'feedback:handle', 'classroom:read', 'leave:read_all',
                'attendance:manage', 'attendance:read')
WHERE r.code = 'ADMIN';

-- TEACHER：录成绩、看评教、查请假、记考勤
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p
  ON p.code IN ('grade:read', 'grade:manage', 'evaluation:read', 'feedback:submit',
                'classroom:read', 'leave:read_all', 'attendance:manage', 'attendance:read')
WHERE r.code = 'TEACHER';

-- STUDENT：看成绩、评教、提建议、查空教室
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p
  ON p.code IN ('grade:read', 'evaluation:submit', 'feedback:submit', 'classroom:read')
WHERE r.code = 'STUDENT';

-- 学生会：学生能力 + 查询请假与班级考勤（只读）
INSERT INTO sys_role (code, name, description, built_in) VALUES
    ('STUDENT_UNION', '学生会', '学生权益与纪律工作：查询请假情况、班级课表与考勤，只读', TRUE);

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p
  ON p.code IN ('grade:read', 'evaluation:submit', 'feedback:submit', 'classroom:read',
                'course:read', 'leave:read_all', 'attendance:read', 'kb:space:read',
                'ai:chat', 'ai:tool:use', 'forum:post:write', 'forum:comment:write')
WHERE r.code = 'STUDENT_UNION';
