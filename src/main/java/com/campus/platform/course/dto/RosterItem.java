package com.campus.platform.course.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "课程选课名单条目")
public record RosterItem(
        Long selectionId,
        Long studentId,
        String username,
        String displayName,
        Long orgUnitId,
        String orgUnitName,
        LocalDateTime selectedAt) {
}
