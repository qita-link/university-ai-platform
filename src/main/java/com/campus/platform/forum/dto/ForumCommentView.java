package com.campus.platform.forum.dto;

import com.campus.platform.forum.entity.ForumCommentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "论坛评论")
public record ForumCommentView(
        Long id,
        Long postId,
        Long parentId,
        Long rootId,
        Long authorId,
        String authorName,
        Long replyToUserId,
        String replyToUserName,
        String content,
        ForumCommentStatus status,
        LocalDateTime createdAt) {
}
