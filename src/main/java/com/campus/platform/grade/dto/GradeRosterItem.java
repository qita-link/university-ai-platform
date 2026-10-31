package com.campus.platform.grade.dto;

import com.campus.platform.grade.entity.GradeStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "成绩录入名单条目")
public record GradeRosterItem(
        Long studentId,
        String username,
        String displayName,
        String orgUnitName,
        BigDecimal score,
        BigDecimal gradePoint,
        String gradeLevel,
        GradeStatus status,
        String remark) {
}
