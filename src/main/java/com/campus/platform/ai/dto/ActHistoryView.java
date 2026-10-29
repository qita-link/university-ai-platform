package com.campus.platform.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一条办事历史。
 *
 * @param steps         当时实际发生的工具调用，界面可原样回放
 * @param pendingStatus 若该次办事生成了确认单，这里是确认单的**当前**状态（可能已被确认/放弃/过期），
 *                      否则为空。历史是快照，但这个字段必须实时，否则“已执行”的事永远显示“待确认”
 */
@Schema(description = "办事历史")
public record ActHistoryView(
        Long id,
        String message,
        String reply,
        String outcome,
        int stepCount,
        List<AgentStep> steps,
        Long pendingId,
        String pendingStatus,
        String model,
        LocalDateTime createdAt) {
}
