package com.campus.platform.course.dto;

import com.campus.platform.course.entity.LeaveStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "请假申请")
public record LeaveView(
        Long id,
        Long studentId,
        String studentUsername,
        String studentName,
        Long classId,
        String className,
        Long courseId,
        String courseCode,
        String courseName,
        Long scheduleId,
        LocalDateTime startAt,
        LocalDateTime endAt,
        String reason,
        LeaveStatus status,
        @Schema(description = "审批顺序快照，如 TEACHER,COUNSELOR") List<String> approvalOrder,
        @Schema(description = "应审批级数") int totalStages,
        @Schema(description = "当前待审批级数") int currentStage,
        @Schema(description = "当前环节角色：TEACHER/COUNSELOR/ADMIN") String currentStageRole,
        List<LeaveApprovalView> approvals,
        Long reviewerId,
        String reviewerName,
        String reviewComment,
        LocalDateTime reviewedAt,
        LocalDateTime revokedAt,
        String revokeNote,
        @Schema(description = "当前用户是否可以审批该申请") boolean canReview,
        @Schema(description = "当前用户是否可以撤销该申请") boolean canCancel,
        @Schema(description = "当前用户是否可以销假") boolean canRevoke,
        LocalDateTime createdAt,
        int version) {
}
