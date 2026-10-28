package com.campus.platform.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "AI 回答引用")
public record AiCitationView(
        Long chunkId,
        Long documentId,
        Long versionId,
        Long spaceId,
        String spaceName,
        String documentTitle,
        String headingPath,
        String snippet,
        double score,
        int rankNo) {
}
