package com.campus.platform.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 调用工具。参数由调用方（模型编排层/前端）按工具定义传入，服务端逐项校验。
 *
 * @param tool           工具名，见 GET /ai/tools
 * @param arguments      参数键值对，可为空对象
 * @param conversationId 关联会话，可为空；仅用于追溯
 */
@Schema(description = "AI 工具调用请求")
public record AiToolCallRequest(
        @Schema(description = "工具名") @NotBlank @Size(max = 64) String tool,
        @Schema(description = "参数键值对") Map<String, Object> arguments,
        @Schema(description = "关联的会话 ID，可空") @Positive Long conversationId) {
}
