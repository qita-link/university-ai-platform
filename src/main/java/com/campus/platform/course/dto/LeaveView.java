package com.campus.platform.course.dto;

import com.campus.platform.course.entity.LeaveStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "请假申请")
public record LeaveView(
        Long id,
        Long studentId,
        String studentUsername,
        String studentName,
        Long courseId,
        String courseCode,
        String courseName,
        Long scheduleId,
        LocalDateTime startAt,
        LocalDateTime endAt,
        String reason,
        LeaveStatus status,
        Long reviewerId,
        String reviewerName,
        String reviewComment,
        LocalDateTime reviewedAt,
        @Schema(description = "当前用户是否可以审批该申请") boolean canReview,
        @Schema(description = "当前用户是否可以撤销该申请") boolean canCancel,
        LocalDateTime createdAt,
        int version) {
}
