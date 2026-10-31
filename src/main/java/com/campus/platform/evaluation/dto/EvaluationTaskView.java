package com.campus.platform.evaluation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "我的评教任务")
public record EvaluationTaskView(
        Long courseId,
        String courseCode,
        String courseName,
        Long semesterId,
        String semesterName,
        Long teacherId,
        String teacherName,
        boolean evaluated,
        BigDecimal myScore) {
}
