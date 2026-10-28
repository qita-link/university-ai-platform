package com.campus.platform.ai.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.campus.platform.common.persistence.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * AI 工具调用记录（ai_tool_call）。
 * <p>
 * 参数以 JSON 快照保存，便于追溯“模型/调用方到底传了什么”；结果只保存可读摘要，
 * 完整业务对象只在本次请求的响应中返回，不落库。
 */
@Getter
@Setter
@TableName("ai_tool_call")
public class AiToolCall extends BaseEntity {

    private Long conversationId;
    private Long userId;
    private String toolName;
    private Boolean sideEffect;
    /** JSON 文本，MySQL JSON 列 */
    private String argumentsJson;
    private String argumentSummary;
    private String preview;
    private AiToolCallStatus status;
    private String resultSummary;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime confirmExpiresAt;
    private LocalDateTime confirmedAt;
    private LocalDateTime executedAt;
    private Integer latencyMs;
    private Integer version;
}
