package com.campus.platform.ai.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 工具调用记录。
 *
 * @param result 仅在“本次请求内实际执行”时返回（只读调用或确认执行），其余场景为 null；
 *               列表/详情只返回 resultSummary
 */
@Schema(description = "AI 工具调用记录")
public record AiToolCallView(
        Long id,
        String toolName,
        boolean sideEffect,
        JsonNode arguments,
        String argumentSummary,
        @Schema(description = "待确认时的目标与影响") String preview,
        @Schema(description = "PENDING_CONFIRM/EXECUTING/SUCCEEDED/FAILED/REJECTED/EXPIRED") String status,
        String resultSummary,
        String errorCode,
        String errorMessage,
        @Schema(description = "确认截止时间；只读工具为空") LocalDateTime confirmExpiresAt,
        LocalDateTime confirmedAt,
        LocalDateTime executedAt,
        Integer latencyMs,
        LocalDateTime createdAt,
        Object result) {
}
