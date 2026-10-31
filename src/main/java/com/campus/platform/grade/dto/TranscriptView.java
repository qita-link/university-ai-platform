package com.campus.platform.grade.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "成绩单：总分绩点 + 按学期分组")
public record TranscriptView(
        GpaSummary overall,
        @Schema(description = "最近一个有成绩的学期（首页卡片用）") SemesterGradeView latest,
        List<SemesterGradeView> semesters) {
}
