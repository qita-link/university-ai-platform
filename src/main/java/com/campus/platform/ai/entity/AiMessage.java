package com.campus.platform.ai.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.campus.platform.common.persistence.BaseEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("ai_message")
public class AiMessage extends BaseEntity {

    private Long conversationId;
    private AiRole role;
    private String content;
    private String model;
    private Integer promptTokens;
    private Integer completionTokens;
    private TokenSource tokenSource;
    private Integer latencyMs;
    private AiMessageStatus status;
    private String errorCode;
}
