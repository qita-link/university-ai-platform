package com.campus.platform.attendance.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.campus.platform.common.persistence.AuditableEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * 考勤场次（attendance_session）：一个行政班在一次课的某一节次上的一条考勤记录。
 * <p>
 * 唯一键 (course_id, class_id, session_date, start_period) 防止重复建场次。
 */
@Getter
@Setter
@TableName("attendance_session")
public class AttendanceSession extends AuditableEntity {

    private Long courseId;
    private Long scheduleId;
    private Long classId;
    private LocalDate sessionDate;
    private Integer startPeriod;
    private Integer endPeriod;
    private AttendanceSessionStatus status;
    private String note;

    @Version
    private Integer version;
}
