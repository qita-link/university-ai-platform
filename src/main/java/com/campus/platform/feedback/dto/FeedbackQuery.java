package com.campus.platform.feedback.dto;

import com.campus.platform.common.api.PageQuery;
import com.campus.platform.feedback.entity.FeedbackCategory;
import com.campus.platform.feedback.entity.FeedbackStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "投诉建议查询")
public class FeedbackQuery extends PageQuery {

    private FeedbackStatus status;

    private FeedbackCategory category;

    @Schema(description = "标题/内容关键词")
    private String keyword;
}
