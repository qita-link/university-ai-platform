package com.campus.platform.retrieval.dto;

import com.campus.platform.retrieval.entity.SearchStrategy;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "知识库检索结果")
public record KnowledgeSearchResult(
        String query,
        SearchStrategy strategy,
        int candidateCount,
        int returnedCount,
        boolean insufficientEvidence,
        double maxScore,
        List<KnowledgeSearchHit> hits) {
}
