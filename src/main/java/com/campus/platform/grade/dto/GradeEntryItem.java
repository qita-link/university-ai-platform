package com.campus.platform.grade.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

@Schema(description = "单条成绩录入；score 为空表示清空")
public record GradeEntryItem(
        @NotNull(message = "学生不能为空") Long studentId,
        @DecimalMin(value = "0", message = "成绩不能小于 0")
        @DecimalMax(value = "100", message = "成绩不能大于 100")
        BigDecimal score,
        @Size(max = 200, message = "备注过长") String remark) {
}
