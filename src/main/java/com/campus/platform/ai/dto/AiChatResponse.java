package com.campus.platform.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "AI 知识库问答响应")
public record AiChatResponse(
        Long conversationId,
        Long userMessageId,
        Long assistantMessageId,
        String answer,
        boolean sufficientEvidence,
        String model,
        List<AiCitationView> citations,
        TokenUsageView tokenUsage) {
}
