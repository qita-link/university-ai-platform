package com.campus.platform.knowledge.dto;

import com.campus.platform.knowledge.entity.SpaceStatus;
import com.campus.platform.knowledge.entity.SpaceVisibility;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "知识库空间")
public record SpaceView(
        Long id,
        Long categoryId,
        String categoryName,
        String name,
        String description,
        SpaceVisibility visibility,
        Long scopeOrgUnitId,
        String scopeOrgName,
        Long scopeCourseId,
        String scopeCourseName,
        Long ownerUserId,
        String ownerName,
        SpaceStatus status,
        long memberCount,
        String accessLevel,
        int version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
