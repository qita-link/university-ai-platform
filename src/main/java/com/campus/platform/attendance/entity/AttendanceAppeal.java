package com.campus.platform.attendance.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.campus.platform.common.persistence.AuditableEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 考勤申诉（attendance_appeal）：学生对某条考勤明细提出异议，由任课教师/导员/管理员复核。
 * <p>
 * 一条明细只能有一条申诉（唯一键），审核通过后可把明细改为出勤/迟到/请假。
 */
@Getter
@Setter
@TableName("attendance_appeal")
public class AttendanceAppeal extends AuditableEntity {

    private Long recordId;
    private Long studentId;
    private String reason;
    private AttendanceStatus targetStatus;
    private AppealStatus status;
    private Long reviewerId;
    private String reviewComment;
    private LocalDateTime reviewedAt;

    @Version
    private Integer version;
}
