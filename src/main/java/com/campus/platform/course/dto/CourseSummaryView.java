package com.campus.platform.course.dto;

import com.campus.platform.course.entity.CourseStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "课程摘要")
public record CourseSummaryView(
        Long id,
        Long semesterId,
        String semesterName,
        String code,
        String name,
        BigDecimal credit,
        String description,
        Long orgUnitId,
        String orgUnitName,
        int capacity,
        int selectedCount,
        CourseStatus status,
        @Schema(description = "授课教师显示名，主讲在前") List<String> teacherNames,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        int version) {
}
