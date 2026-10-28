package com.campus.platform.course.dto;

import com.campus.platform.course.entity.WeekParity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "新增/修改排课")
public record ScheduleRequest(
        @Schema(description = "星期，1=周一 … 7=周日") @NotNull @Min(1) @Max(7) Integer dayOfWeek,
        @Schema(description = "开始节次") @NotNull @Min(1) @Max(12) Integer startPeriod,
        @Schema(description = "结束节次") @NotNull @Min(1) @Max(12) Integer endPeriod,
        @Schema(description = "开始周") @NotNull @Min(1) @Max(30) Integer startWeek,
        @Schema(description = "结束周") @NotNull @Min(1) @Max(30) Integer endWeek,
        @Schema(description = "ALL 每周 / ODD 单周 / EVEN 双周；默认 ALL") WeekParity weekParity,
        @Schema(description = "教室") @Size(max = 64) String classroom) {
}
