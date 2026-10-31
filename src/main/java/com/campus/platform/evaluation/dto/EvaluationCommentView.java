package com.campus.platform.evaluation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "评教留言（不返回学生身份）")
public record EvaluationCommentView(
        String comment,
        BigDecimal totalScore,
        LocalDateTime createdAt) {
}
