package com.campus.platform.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "AI 知识库问答请求")
public record AiChatRequest(
        @Schema(description = "用户问题") @NotBlank @Size(max = 2000) String message,
        @Schema(description = "继续已有会话；为空则新建") @Positive Long conversationId,
        @Schema(description = "只在这些空间内检索；为空表示所有有权空间")
        @Size(max = 50) List<@Positive Long> spaceIds) {
}
