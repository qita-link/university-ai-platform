package com.campus.platform.feedback.dto;

import com.campus.platform.feedback.entity.FeedbackCategory;
import com.campus.platform.feedback.entity.FeedbackStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "投诉建议")
public record FeedbackView(
        Long id,
        FeedbackCategory category,
        String title,
        String content,
        boolean anonymous,
        @Schema(description = "匿名时为空") Long submitterId,
        @Schema(description = "匿名时为空") String submitterName,
        FeedbackStatus status,
        String handlerName,
        String reply,
        LocalDateTime repliedAt,
        boolean canHandle,
        LocalDateTime createdAt) {
}
