package com.campus.platform.grade.dto;

import com.campus.platform.grade.entity.GradeStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "单条成绩（学生视角）")
public record GradeView(
        Long id,
        Long courseId,
        String courseCode,
        String courseName,
        BigDecimal credit,
        Long semesterId,
        String semesterCode,
        String semesterName,
        BigDecimal score,
        BigDecimal gradePoint,
        String gradeLevel,
        GradeStatus status,
        String remark,
        LocalDateTime publishedAt) {
}
