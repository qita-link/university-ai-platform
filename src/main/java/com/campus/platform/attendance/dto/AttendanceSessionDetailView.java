package com.campus.platform.attendance.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "考勤场次详情")
public record AttendanceSessionDetailView(
        AttendanceSessionView session,
        List<AttendanceRecordView> records) {
}
