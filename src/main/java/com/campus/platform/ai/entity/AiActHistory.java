package com.campus.platform.ai.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.campus.platform.common.persistence.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 一次自然语言办事的记录（ai_act_history）。
 * <p>
 * 与 {@link AiToolCall} 的关系：一次办事可能调用多个工具（含写操作的待确认单），
 * 本表只保存“用户说了什么、模型怎么答、走过哪些步骤”，具体每次工具调用的参数与结果
 * 仍以 {@code ai_tool_call} 为准，两个表通过 {@code steps_json} 里的 callId 对应。
 */
@Getter
@Setter
@TableName("ai_act_history")
public class AiActHistory extends BaseEntity {

    private Long userId;
    private String message;
    private String reply;
    /** DONE / WAITING_CONFIRM / MAX_ROUNDS / RAW_TEXT */
    private String outcome;
    private Integer stepCount;
    /** AgentStep 数组的 JSON 快照，供界面回放 */
    private String stepsJson;
    private Long pendingId;
    private String model;
}
