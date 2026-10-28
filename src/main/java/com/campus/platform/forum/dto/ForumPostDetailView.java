package com.campus.platform.forum.dto;

import com.campus.platform.forum.entity.ForumPostStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "论坛帖子详情")
public record ForumPostDetailView(
        Long id,
        Long categoryId,
        String categoryName,
        Long authorId,
        String authorName,
        String title,
        String content,
        ForumPostStatus status,
        boolean pinned,
        int commentCount,
        LocalDateTime lastActivityAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        int version) {
}
