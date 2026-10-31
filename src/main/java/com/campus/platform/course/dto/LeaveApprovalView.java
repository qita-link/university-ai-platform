package com.campus.platform.course.dto;

import com.campus.platform.course.entity.LeaveApproverRole;
import com.campus.platform.course.entity.LeaveDecision;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "一级审批记录")
public record LeaveApprovalView(
        int stage,
        LeaveApproverRole approverRole,
        Long approverId,
        String approverName,
        LeaveDecision decision,
        String comment,
        LocalDateTime decidedAt) {
}
