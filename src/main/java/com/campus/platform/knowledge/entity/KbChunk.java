package com.campus.platform.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.campus.platform.common.persistence.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 文档块（kb_chunk）。只有当前版本的 ACTIVE 块参与检索。 */
@Getter
@Setter
@TableName("kb_chunk")
public class KbChunk extends BaseEntity {

    private Long documentId;
    private Long versionId;
    private Long spaceId;
    private String documentTitle;
    private Integer chunkIndex;
    private String headingPath;
    private String content;
    private Integer charCount;
    private Integer tokenEstimate;
    private ChunkStatus status;
    private EmbeddingStatus embeddingStatus;
    private String vectorId;
}
