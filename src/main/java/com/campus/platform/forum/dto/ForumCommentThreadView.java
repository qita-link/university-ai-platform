package com.campus.platform.forum.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "评论线程：顶级评论及其全部回复")
public record ForumCommentThreadView(
        ForumCommentView comment,
        List<ForumCommentView> replies) {
}
