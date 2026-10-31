package com.campus.platform.forum.dto;

import com.campus.platform.forum.entity.ReportStatus;
import com.campus.platform.forum.entity.ReportTargetType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "举报记录（审核队列）")
public record ForumReportView(
        Long id,
        ReportTargetType targetType,
        Long targetId,
        @Schema(description = "被举报内容的标题或摘要，便于审核时定位") String targetTitle,
        @Schema(description = "被举报内容当前是否仍可见") boolean targetVisible,
        Long reporterId,
        String reporterName,
        String reason,
        String detail,
        ReportStatus status,
        Long handledBy,
        String handlerName,
        LocalDateTime handledAt,
        String handleNote,
        LocalDateTime createdAt) {
}
