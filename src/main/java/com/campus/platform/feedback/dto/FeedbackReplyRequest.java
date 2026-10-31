package com.campus.platform.feedback.dto;

import com.campus.platform.feedback.entity.FeedbackStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "处理投诉建议")
public record FeedbackReplyRequest(
        @NotNull(message = "请选择处理后的状态") FeedbackStatus status,
        @Size(max = 1000, message = "回复最多 1000 字") String reply) {
}
