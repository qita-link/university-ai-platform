package com.campus.platform.attendance.dto;

import com.campus.platform.attendance.entity.AttendanceStatus;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "考勤明细")
public record AttendanceRecordView(
        Long studentId,
        String username,
        String displayName,
        String orgUnitName,
        AttendanceStatus status,
        Long leaveId,
        String remark) {
}
