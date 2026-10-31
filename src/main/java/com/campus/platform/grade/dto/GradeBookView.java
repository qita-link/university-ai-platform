package com.campus.platform.grade.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

@Schema(description = "课程成绩册（教师录入用）")
public record GradeBookView(
        Long courseId,
        String courseCode,
        String courseName,
        BigDecimal credit,
        Long semesterId,
        String semesterName,
        @Schema(description = "是否已有已发布成绩") boolean published,
        List<GradeRosterItem> items) {
}
