package com.campus.platform.attendance.entity;

/** 考勤状态。LEAVE 表示该次课有已通过的请假，不计旷课。 */
public enum AttendanceStatus {
    PRESENT,
    ABSENT,
    LATE,
    LEAVE;

    public boolean isAbsence() {
        return this == ABSENT;
    }
}
