package com.campus.platform.forum.dto;

import com.campus.platform.common.api.PageQuery;
import com.campus.platform.forum.entity.ForumPostStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "论坛帖子查询")
public class ForumPostQuery extends PageQuery {

    @Schema(description = "分类 ID")
    @Positive
    private Long categoryId;

    @Schema(description = "关键词（标题/正文，中文按 2 字 ngram 检索）")
    @Size(max = 100)
    private String keyword;

    @Schema(description = "作者 ID，用于查看某人的帖子")
    @Positive
    private Long authorId;

    @Schema(description = "帖子状态，默认 PUBLISHED；非 PUBLISHED 仅版主/管理员可用")
    private ForumPostStatus status;
}
