package com.campus.platform.attendance.entity;

/** 考勤场次状态：DRAFT 可修改，FINAL 已提交（修改需先由管理员处理）。 */
public enum AttendanceSessionStatus {
    DRAFT,
    FINAL
}
