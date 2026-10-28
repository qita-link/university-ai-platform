package com.campus.platform.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "AI 对话详情（消息与引用）")
public record ConversationDetailView(
        Long id,
        String title,
        String status,
        LocalDateTime lastMessageAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<AiMessageView> messages) {
}
