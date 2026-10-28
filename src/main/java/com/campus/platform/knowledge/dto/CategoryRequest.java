package com.campus.platform.knowledge.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@Schema(description = "新建/修改知识库分类")
public record CategoryRequest(
        @Schema(description = "上级分类，顶级为空") @Positive Long parentId,
        @Schema(description = "分类名称") @NotBlank @Size(max = 64) String name,
        @Schema(description = "分类描述") @Size(max = 500) String description,
        @Schema(description = "同级排序，越小越靠前") @Min(0) @Max(100000) Integer sortOrder) {
}
