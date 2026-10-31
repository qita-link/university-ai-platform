package com.campus.platform.forum.dto;

import com.campus.platform.forum.entity.ForumPostStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "论坛帖子摘要")
public record ForumPostSummaryView(
        Long id,
        Long categoryId,
        String categoryName,
        Long authorId,
        String authorName,
        int authorAvatarVersion,
        String title,
        String excerpt,
        ForumPostStatus status,
        boolean pinned,
        LocalDateTime pinnedUntil,
        int commentCount,
        LocalDateTime lastActivityAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        int version) {
}
