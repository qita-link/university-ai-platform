package com.campus.platform.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 自然语言办事的返回。
 *
 * @param reply   给用户的答复（模型原话，或“已生成待确认操作”）
 * @param steps   本次实际发生的工具调用，按发生顺序
 * @param pending 写操作的待确认记录；为空表示没有需要确认的动作
 * @param outcome DONE（模型已答复）/ WAITING_CONFIRM（等用户确认）/ MAX_ROUNDS（轮次用尽）/ RAW_TEXT（模型没按协议输出，原样回显）
 * @param model   实际使用的模型
 */
@Schema(description = "自然语言办事结果")
public record AgentActResponse(
        String reply,
        List<AgentStep> steps,
        AiToolCallView pending,
        @Schema(description = "DONE/WAITING_CONFIRM/MAX_ROUNDS/RAW_TEXT") String outcome,
        String model) {
}
