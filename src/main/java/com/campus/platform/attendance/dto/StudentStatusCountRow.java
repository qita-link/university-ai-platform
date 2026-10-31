package com.campus.platform.attendance.dto;

import com.campus.platform.attendance.entity.AttendanceStatus;
import lombok.Getter;
import lombok.Setter;

/** 按学生与状态统计的考勤数量（查询映射用）。 */
@Getter
@Setter
public class StudentStatusCountRow {

    private Long studentId;
    private AttendanceStatus status;
    private Integer cnt;
}
