package com.campus.platform.grade.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "绩点与加权平均汇总")
public record GpaSummary(
        @Schema(description = "平均学分绩点，按学分加权") BigDecimal gpa,
        @Schema(description = "加权平均分") BigDecimal weightedAverage,
        @Schema(description = "总学分（参与计算）") BigDecimal totalCredits,
        @Schema(description = "获得学分（绩点大于 0）") BigDecimal earnedCredits,
        int courseCount) {
}
