package com.campus.platform.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 自然语言办事请求。
 *
 * @param message 用户的一句话诉求，例如“把大学物理排到周五 5-6 节”
 */
@Schema(description = "自然语言办事请求")
public record AgentActRequest(
        @Schema(description = "用户诉求", example = "帮我把大学物理排到周五 5-6 节，教室 E203")
        @NotBlank @Size(max = 1000) String message) {
}
