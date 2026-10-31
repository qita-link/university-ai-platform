package com.campus.platform.attendance.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "班级考勤统计")
public record ClassAttendanceStatsView(
        Long classId,
        String className,
        Long semesterId,
        String semesterName,
        int sessionCount,
        List<ClassAttendanceStat> students) {
}
