package com.campus.platform.forum.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@Schema(description = "发表评论或回复")
public record ForumCommentCreateRequest(
        @Schema(description = "评论内容") @NotBlank @Size(max = 2000) String content,
        @Schema(description = "回复的评论 ID；为空表示顶级评论") @Positive Long parentId) {
}
