package com.campus.platform.attendance.dto;

import com.campus.platform.attendance.entity.AppealStatus;
import com.campus.platform.attendance.entity.AttendanceStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Schema(description = "考勤申诉")
public record AppealView(
        Long id,
        Long recordId,
        Long studentId,
        String studentName,
        String studentUsername,
        Long courseId,
        String courseName,
        LocalDate sessionDate,
        int startPeriod,
        int endPeriod,
        AttendanceStatus originalStatus,
        AttendanceStatus targetStatus,
        String reason,
        AppealStatus status,
        String reviewerName,
        String reviewComment,
        LocalDateTime reviewedAt,
        boolean canReview,
        LocalDateTime createdAt) {
}
