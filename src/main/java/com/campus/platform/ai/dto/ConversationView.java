package com.campus.platform.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "AI 对话摘要")
public record ConversationView(
        Long id,
        String title,
        String status,
        LocalDateTime lastMessageAt,
        long messageCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
