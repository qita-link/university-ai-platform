package com.campus.platform.evaluation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

@Schema(description = "单门课程的评教聚合")
public record EvaluationCourseSummary(
        Long courseId,
        String courseCode,
        String courseName,
        Long semesterId,
        String semesterName,
        int count,
        BigDecimal avgTeaching,
        BigDecimal avgContent,
        BigDecimal avgInteraction,
        BigDecimal avgWorkload,
        BigDecimal avgTotal,
        List<EvaluationCommentView> comments) {
}
