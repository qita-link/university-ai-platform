package com.campus.platform.knowledge.dto;

import com.campus.platform.knowledge.entity.ChunkStatus;
import com.campus.platform.knowledge.entity.EmbeddingStatus;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "文档块")
public record ChunkView(
        Long id,
        Long versionId,
        int chunkIndex,
        String headingPath,
        String content,
        int charCount,
        int tokenEstimate,
        ChunkStatus status,
        EmbeddingStatus embeddingStatus,
        String vectorId) {
}
