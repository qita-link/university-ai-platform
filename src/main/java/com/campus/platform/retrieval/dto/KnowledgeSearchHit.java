package com.campus.platform.retrieval.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "知识库检索命中")
public record KnowledgeSearchHit(
        Long chunkId,
        Long documentId,
        Long versionId,
        Long spaceId,
        String spaceName,
        String documentTitle,
        String headingPath,
        String snippet,
        String content,
        double score,
        int rank) {
}
