package com.campus.platform.evaluation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

@Schema(description = "教师评教总览")
public record EvaluationOverview(
        int totalCount,
        BigDecimal avgTotal,
        List<EvaluationCourseSummary> courses) {
}
