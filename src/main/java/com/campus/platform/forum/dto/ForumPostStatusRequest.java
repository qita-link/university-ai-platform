package com.campus.platform.forum.dto;

import com.campus.platform.forum.entity.ForumPostStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

@Schema(description = "帖子隐藏/恢复/置顶；status 与 pinned 至少提供一个")
public record ForumPostStatusRequest(
        @Schema(description = "目标状态：PUBLISHED（恢复）或 HIDDEN（隐藏）；不能改为 DELETED") ForumPostStatus status,
        @Schema(description = "是否置顶") Boolean pinned,
        @Schema(description = "置顶天数：0 = 长期置顶，不传默认 7 天，上限 90 天")
        @Min(0) @Max(90) Integer pinnedDays,
        @Schema(description = "操作原因，写入审计") @Size(max = 500) String reason) {
}
