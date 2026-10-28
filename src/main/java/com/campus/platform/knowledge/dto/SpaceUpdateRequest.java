package com.campus.platform.knowledge.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@Schema(description = "修改知识库（整体更新）")
public record SpaceUpdateRequest(
        @Schema(description = "分类 ID，可空") @Positive Long categoryId,
        @Schema(description = "名称") @NotBlank @Size(max = 128) String name,
        @Schema(description = "描述") @Size(max = 1000) String description,
        @Schema(description = "PUBLIC/ORG/PRIVATE/COURSE") @NotNull String visibility,
        @Schema(description = "visibility=ORG 时的组织范围") @Positive Long scopeOrgUnitId,
        @Schema(description = "visibility=COURSE 时的课程") @Positive Long scopeCourseId,
        @Schema(description = "读取时得到的 version") @NotNull @Min(0) Integer version) {
}
