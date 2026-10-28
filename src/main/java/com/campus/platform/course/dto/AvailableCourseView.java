package com.campus.platform.course.dto;

import com.campus.platform.course.entity.CourseStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

@Schema(description = "可选课程：带可选择性判断，前端/AI 工具可直接展示原因")
public record AvailableCourseView(
        Long courseId,
        Long semesterId,
        String code,
        String name,
        BigDecimal credit,
        int capacity,
        int selectedCount,
        CourseStatus status,
        List<String> teacherNames,
        List<ScheduleView> schedules,
        @Schema(description = "当前是否可选") boolean selectable,
        @Schema(description = "不可选原因，可选时为 null") CourseBlockReason blockedReason,
        @Schema(description = "冲突课程名称，仅 TIME_CONFLICT 时有值") String conflictCourseName) {
}
