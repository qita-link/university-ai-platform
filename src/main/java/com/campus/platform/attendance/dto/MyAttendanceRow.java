package com.campus.platform.attendance.dto;

import com.campus.platform.attendance.entity.AppealStatus;
import com.campus.platform.attendance.entity.AttendanceStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/** 学生本人考勤明细（含申诉状态），查询映射用。 */
@Getter
@Setter
public class MyAttendanceRow {

    private Long recordId;
    private AttendanceStatus status;
    private String remark;
    private Long sessionId;
    private LocalDate sessionDate;
    private Integer startPeriod;
    private Integer endPeriod;
    private Long courseId;
    private String courseCode;
    private String courseName;
    private Long appealId;
    private AppealStatus appealStatus;
}
