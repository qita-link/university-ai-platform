package com.campus.platform.forum.dto;

import com.campus.platform.forum.entity.ForumCategoryStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "论坛分类")
public record ForumCategoryView(
        Long id,
        String name,
        String description,
        int sortOrder,
        ForumCategoryStatus status,
        boolean announcement,
        long postCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
