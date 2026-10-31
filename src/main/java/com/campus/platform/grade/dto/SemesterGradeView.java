package com.campus.platform.grade.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "按学期分组的成绩")
public record SemesterGradeView(
        Long semesterId,
        String semesterCode,
        String semesterName,
        GpaSummary summary,
        List<GradeView> grades) {
}
