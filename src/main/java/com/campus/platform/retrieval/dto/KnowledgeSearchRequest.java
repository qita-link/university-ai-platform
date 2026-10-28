package com.campus.platform.retrieval.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "知识库关键词检索请求")
public record KnowledgeSearchRequest(
        @Schema(description = "查询文本") @NotBlank @Size(max = 500) String query,
        @Schema(description = "只在这些空间内检索；为空表示所有有权空间")
        @Size(max = 50) List<@Positive Long> spaceIds,
        @Schema(description = "返回条数，1~20") @Min(1) @Max(20) Integer topK,
        @Schema(description = "最低相关性分数，默认取服务端配置") @Min(0) Double minScore) {
}
