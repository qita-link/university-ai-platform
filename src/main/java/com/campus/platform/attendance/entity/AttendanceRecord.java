package com.campus.platform.attendance.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.campus.platform.common.persistence.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 考勤明细（attendance_record）：一个学生在一个场次上的状态。 */
@Getter
@Setter
@TableName("attendance_record")
public class AttendanceRecord extends BaseEntity {

    private Long sessionId;
    private Long studentId;
    private AttendanceStatus status;
    private Long leaveId;
    private String remark;
}
