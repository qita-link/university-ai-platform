-- =====================================================================
-- V11: 站内通知、考勤申诉与缺勤预警、教室借用
-- 约定与 V1~V10 一致：InnoDB / utf8mb4 / DATETIME(3) / BIGINT 自增主键。
-- 设计要点：
--   1) 通知先落站内信，再按启用的渠道（邮件/微信）生成投递记录；
--      渠道发送器是可替换的扩展点，当前未接入时投递记录标记 SKIPPED，不丢消息。
--   2) 考勤申诉一条明细一条申诉（唯一键），审核通过可把状态改为出勤/迟到/请假；
--      缺勤预警按「学生+课程+累计次数」去重，避免每次课都重复提醒。
--   3) 教室借用与已发布课表、课程排课、已批准借用三重校验，审批时再校验一次防并发。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 站内通知与投递
-- ---------------------------------------------------------------------
CREATE TABLE notification (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    user_id    BIGINT        NOT NULL,
    category   VARCHAR(32)   NOT NULL COMMENT 'LEAVE/GRADE/FEEDBACK/ATTENDANCE/ROOM/SYSTEM',
    level      VARCHAR(16)   NOT NULL DEFAULT 'INFO' COMMENT 'INFO/SUCCESS/WARNING',
    title      VARCHAR(120)  NOT NULL,
    body       VARCHAR(1000) NULL,
    link_url   VARCHAR(255)  NULL COMMENT '前端路由，如 /leave?tab=review',
    biz_type   VARCHAR(32)   NULL COMMENT '业务类型，便于追溯',
    biz_id     BIGINT        NULL,
    read_at    DATETIME(3)   NULL,
    created_at DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_notification_user (user_id, read_at, id),
    KEY idx_notification_biz (biz_type, biz_id),
    CONSTRAINT fk_notification_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '站内通知';

-- 投递记录：IN_APP 不落此表；EMAIL/WECHAT 在渠道启用时生成，未接入的发送器写 SKIPPED。
CREATE TABLE notification_delivery (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    notification_id BIGINT       NOT NULL,
    channel         VARCHAR(16)  NOT NULL COMMENT 'EMAIL/WECHAT',
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENT/FAILED/SKIPPED',
    attempts        INT          NOT NULL DEFAULT 0,
    last_error      VARCHAR(500) NULL,
    sent_at         DATETIME(3)  NULL,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_delivery (notification_id, channel),
    KEY idx_delivery_status (status, id),
    CONSTRAINT fk_delivery_notification FOREIGN KEY (notification_id) REFERENCES notification (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '通知投递记录';

-- ---------------------------------------------------------------------
-- 2. 考勤申诉与缺勤预警
-- ---------------------------------------------------------------------
CREATE TABLE attendance_appeal (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    record_id      BIGINT       NOT NULL,
    student_id     BIGINT       NOT NULL,
    reason         VARCHAR(500) NOT NULL,
    target_status  VARCHAR(16)  NOT NULL DEFAULT 'PRESENT' COMMENT '申诉希望改为 PRESENT/LATE/LEAVE',
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED',
    reviewer_id    BIGINT       NULL,
    review_comment VARCHAR(500) NULL,
    reviewed_at    DATETIME(3)  NULL,
    version        INT          NOT NULL DEFAULT 0,
    created_by     BIGINT       NULL,
    updated_by     BIGINT       NULL,
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_appeal_record (record_id) COMMENT '一条考勤明细只能有一条申诉',
    KEY idx_appeal_student (student_id, status, id),
    KEY idx_appeal_status (status, id),
    CONSTRAINT fk_appeal_record FOREIGN KEY (record_id) REFERENCES attendance_record (id) ON DELETE CASCADE,
    CONSTRAINT fk_appeal_student FOREIGN KEY (student_id) REFERENCES sys_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_appeal_reviewer FOREIGN KEY (reviewer_id) REFERENCES sys_user (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '考勤申诉';

-- 预警去重：同一学生同一课程在同一累计次数上只提醒一次。
CREATE TABLE attendance_warning (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    student_id   BIGINT      NOT NULL,
    course_id    BIGINT      NOT NULL,
    absent_count INT         NOT NULL,
    created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_attendance_warning (student_id, course_id, absent_count),
    KEY idx_warning_course (course_id, created_at),
    CONSTRAINT fk_warning_student FOREIGN KEY (student_id) REFERENCES sys_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_warning_course FOREIGN KEY (course_id) REFERENCES course (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '缺勤预警';

-- ---------------------------------------------------------------------
-- 3. 教室借用
-- ---------------------------------------------------------------------
CREATE TABLE room_booking (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    room_id        BIGINT       NOT NULL,
    applicant_id   BIGINT       NOT NULL,
    booking_date   DATE         NOT NULL,
    start_period   TINYINT      NOT NULL,
    end_period     TINYINT      NOT NULL,
    purpose        VARCHAR(200) NOT NULL,
    attendees      INT          NULL COMMENT '预计人数',
    contact        VARCHAR(64)  NULL COMMENT '联系方式',
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED/CANCELLED',
    reviewer_id    BIGINT       NULL,
    review_comment VARCHAR(500) NULL,
    reviewed_at    DATETIME(3)  NULL,
    version        INT          NOT NULL DEFAULT 0,
    created_by     BIGINT       NULL,
    updated_by     BIGINT       NULL,
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_booking_room_date (room_id, booking_date, status),
    KEY idx_booking_applicant (applicant_id, id),
    KEY idx_booking_status (status, id),
    CONSTRAINT fk_booking_room FOREIGN KEY (room_id) REFERENCES sch_room (id) ON DELETE RESTRICT,
    CONSTRAINT fk_booking_applicant FOREIGN KEY (applicant_id) REFERENCES sys_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_booking_reviewer FOREIGN KEY (reviewer_id) REFERENCES sys_user (id) ON DELETE SET NULL,
    CONSTRAINT ck_booking_period CHECK (start_period BETWEEN 1 AND 12 AND end_period BETWEEN start_period AND 12),
    CONSTRAINT ck_booking_attendees CHECK (attendees IS NULL OR attendees BETWEEN 1 AND 5000)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '教室借用申请';

-- ---------------------------------------------------------------------
-- 4. 权限与设置
-- ---------------------------------------------------------------------
INSERT INTO sys_permission (code, name, module) VALUES
    ('attendance:appeal',   '对本人考勤提出申诉', 'attendance'),
    ('room:book',           '申请借用教室',       'classroom'),
    ('room:approve',        '审批教室借用',       'classroom');

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p
  ON p.code IN ('attendance:appeal', 'room:book', 'room:approve')
WHERE r.code = 'ADMIN';

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p
  ON p.code IN ('room:book')
WHERE r.code = 'TEACHER';

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p
  ON p.code IN ('attendance:appeal', 'room:book')
WHERE r.code = 'STUDENT';

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p
  ON p.code IN ('attendance:appeal', 'room:book')
WHERE r.code = 'STUDENT_UNION';

INSERT INTO platform_setting (setting_key, setting_value, description) VALUES
    ('attendance.warning.threshold', '3',   '同一课程累计旷课达到该次数时提醒学生与导员'),
    ('attendance.appeal.window.days','7',   '考勤申诉期限（自上课日起的天数）'),
    ('notify.channel.email.enabled', 'false','邮件推送开关（渠道尚未接入，开启后投递记录为 SKIPPED）'),
    ('notify.channel.wechat.enabled','false','微信服务号推送开关（渠道尚未接入）'),
    ('room.booking.advance.days',    '14',  '教室借用可提前申请的天数');
