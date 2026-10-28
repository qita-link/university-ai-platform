package com.campus.platform.forum.dto;

import com.campus.platform.forum.entity.ForumPostStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "帖子隐藏/恢复/置顶；status 与 pinned 至少提供一个")
public record ForumPostStatusRequest(
        @Schema(description = "目标状态：PUBLISHED（恢复）或 HIDDEN（隐藏）；不能改为 DELETED") ForumPostStatus status,
        @Schema(description = "是否置顶") Boolean pinned,
        @Schema(description = "操作原因，写入审计") @Size(max = 500) String reason) {
}
