package com.campus.platform.forum.dto;

import com.campus.platform.forum.entity.ForumCategoryStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "新建/修改论坛分类")
public record ForumCategoryRequest(
        @Schema(description = "分类名称") @NotBlank @Size(max = 64) String name,
        @Schema(description = "分类描述") @Size(max = 500) String description,
        @Schema(description = "排序，越小越靠前") @Min(0) @Max(100000) Integer sortOrder,
        @Schema(description = "状态；新建时默认 ACTIVE") ForumCategoryStatus status,
        @Schema(description = "是否公告分类；公告分类发帖需 forum:announce，发布后广播全员")
        Boolean announcement) {
}
