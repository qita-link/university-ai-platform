package com.campus.platform.course.dto;

import com.campus.platform.course.entity.SelectionStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "选课记录")
public record SelectionView(
        Long id,
        Long courseId,
        String courseCode,
        String courseName,
        BigDecimal credit,
        Long semesterId,
        String semesterName,
        SelectionStatus status,
        List<String> teacherNames,
        LocalDateTime selectedAt,
        LocalDateTime droppedAt) {
}
