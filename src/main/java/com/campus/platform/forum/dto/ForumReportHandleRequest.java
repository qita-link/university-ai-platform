package com.campus.platform.forum.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "举报处理请求")
public record ForumReportHandleRequest(
        @Schema(description = "true=举报成立（隐藏帖子/删除评论）；false=驳回")
        @NotNull(message = "accept 不能为空") Boolean accept,
        @Schema(description = "处理备注，写入审计") @Size(max = 500) String note) {
}
