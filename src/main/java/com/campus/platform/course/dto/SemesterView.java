package com.campus.platform.course.dto;

import com.campus.platform.course.entity.SemesterStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Schema(description = "学期详情")
public record SemesterView(
        Long id,
        String code,
        String name,
        LocalDate startDate,
        LocalDate endDate,
        LocalDateTime selectionStartAt,
        LocalDateTime selectionEndAt,
        SemesterStatus status,
        @Schema(description = "当前是否处于选课窗口内（学期 ACTIVE 且时间在窗口内）") boolean selectionOpen,
        @Schema(description = "该学期课程数") long courseCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
