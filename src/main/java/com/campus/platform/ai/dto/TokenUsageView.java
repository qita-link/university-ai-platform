package com.campus.platform.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Token 用量")
public record TokenUsageView(
        int promptTokens,
        int completionTokens,
        int totalTokens,
        String source) {
}
