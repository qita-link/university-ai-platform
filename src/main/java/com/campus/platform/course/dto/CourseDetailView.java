package com.campus.platform.course.dto;

import com.campus.platform.course.entity.CourseStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "课程详情")
public record CourseDetailView(
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
        List<CourseTeacherView> teachers,
        List<ScheduleView> schedules,
        @Schema(description = "本人当前选课状态；教师/管理员为 null") String mySelectionStatus,
        @Schema(description = "本人当前是否可以选课") boolean selectable,
        @Schema(description = "不可选原因，可空") CourseBlockReason blockedReason,
        @Schema(description = "选课窗口是否开放") boolean selectionOpen,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        int version) {
}
