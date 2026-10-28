package com.campus.platform.ai.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.campus.platform.common.persistence.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("ai_conversation")
public class AiConversation extends BaseEntity {

    private Long userId;
    private String title;
    private ConversationStatus status;
    private LocalDateTime lastMessageAt;
}
