package com.campus.platform.grade.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "绩点换算规则")
public record GradeRuleView(
        Long id,
        String name,
        BigDecimal minScore,
        BigDecimal maxScore,
        BigDecimal gradePoint,
        String gradeLevel) {
}
