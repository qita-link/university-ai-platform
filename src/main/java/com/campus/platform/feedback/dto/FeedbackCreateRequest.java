package com.campus.platform.feedback.dto;

import com.campus.platform.feedback.entity.FeedbackCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "提交投诉或建议")
public record FeedbackCreateRequest(
        @NotNull(message = "请选择分类") FeedbackCategory category,
        @NotBlank(message = "请填写标题")
        @Size(max = 120, message = "标题最多 120 字") String title,
        @NotBlank(message = "请填写内容")
        @Size(max = 2000, message = "内容最多 2000 字") String content,
        @Schema(description = "是否匿名，默认否") Boolean anonymous) {
}
