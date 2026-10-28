package com.campus.platform.ai.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.campus.platform.common.persistence.BaseEntity;
import com.campus.platform.retrieval.entity.SearchStrategy;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("ai_retrieval_record")
public class AiRetrievalRecord extends BaseEntity {

    private Long messageId;
    private String queryText;
    private String rewrittenQuery;
    private SearchStrategy strategy;
    private Integer candidateCount;
    private Integer selectedCount;
    private Integer contextTokens;
    private Integer latencyMs;
}
