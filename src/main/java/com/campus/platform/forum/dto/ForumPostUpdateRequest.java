package com.campus.platform.forum.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@Schema(description = "修改帖子（整体更新）")
public record ForumPostUpdateRequest(
        @Schema(description = "分类 ID") @NotNull @Positive Long categoryId,
        @Schema(description = "标题") @NotBlank @Size(max = 200) String title,
        @Schema(description = "正文") @NotBlank @Size(max = 50000) String content,
        @Schema(description = "乐观锁版本") @NotNull @Min(0) Integer version) {
}
