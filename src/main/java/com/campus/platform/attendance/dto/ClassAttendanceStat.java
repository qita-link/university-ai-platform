package com.campus.platform.attendance.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "单个学生的考勤统计")
public record ClassAttendanceStat(
        Long studentId,
        String username,
        String displayName,
        int total,
        int present,
        int absent,
        int late,
        int leave,
        @Schema(description = "旷课率，百分比") double absenceRate) {
}
