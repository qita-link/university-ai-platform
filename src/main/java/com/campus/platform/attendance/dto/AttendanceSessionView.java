package com.campus.platform.attendance.dto;

import com.campus.platform.attendance.entity.AttendanceSessionStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Schema(description = "考勤场次")
public record AttendanceSessionView(
        Long id,
        Long courseId,
        String courseCode,
        String courseName,
        Long classId,
        String className,
        LocalDate sessionDate,
        int startPeriod,
        int endPeriod,
        AttendanceSessionStatus status,
        String note,
        int presentCount,
        int absentCount,
        int lateCount,
        int leaveCount,
        LocalDateTime updatedAt) {
}
