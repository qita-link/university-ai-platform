package com.campus.platform.ai.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.campus.platform.common.persistence.BaseEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("ai_message_citation")
public class AiMessageCitation extends BaseEntity {

    private Long messageId;
    private Long chunkId;
    private Long documentId;
    private Long versionId;
    private Integer rankNo;
    private Double score;
    private Boolean usedInContext;
    private String snippet;
}
