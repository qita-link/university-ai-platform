package com.campus.platform.ai.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 办事过程中的一步：模型选了哪个工具、传了什么参数、结果如何。
 * <p>
 * 每一步都对应一条 {@code ai_tool_call} 记录（只读调用即执行、写操作生成待确认），
 * 因此界面上的“执行轨迹”与审计日志能一一对上。
 *
 * @param roundNo     第几轮
 * @param toolName    工具名
 * @param title       工具标题
 * @param sideEffect  是否写操作
 * @param arguments   实际参数
 * @param status      SUCCEEDED / FAILED / PENDING_CONFIRM
 * @param summary     可读结果摘要
 * @param errorCode   失败错误码
 * @param errorMessage 失败原因
 * @param callId      对应的工具调用记录 ID
 */
@Schema(description = "办事执行轨迹中的一步")
public record AgentStep(
        int roundNo,
        String toolName,
        String title,
        boolean sideEffect,
        JsonNode arguments,
        String status,
        String summary,
        String errorCode,
        String errorMessage,
        Long callId) {
}
