package com.campus.platform.ai.dto;

import com.campus.platform.ai.entity.AiMessageStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "AI 对话消息")
public record AiMessageView(
        Long id,
        String role,
        String content,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        String tokenSource,
        Integer latencyMs,
        AiMessageStatus status,
        String errorCode,
        LocalDateTime createdAt,
        java.util.List<AiCitationView> citations) {
}
