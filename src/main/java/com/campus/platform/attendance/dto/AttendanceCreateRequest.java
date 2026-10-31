package com.campus.platform.attendance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

@Schema(description = "新建考勤场次")
public record AttendanceCreateRequest(
        @NotNull(message = "课程不能为空") Long courseId,
        @Schema(description = "对应排课，可为空（手动补录）") Long scheduleId,
        @NotNull(message = "班级不能为空") Long classId,
        @NotNull(message = "上课日期不能为空") LocalDate sessionDate,
        @NotNull(message = "开始节次不能为空") @Min(value = 1, message = "节次 1~12") @Max(value = 12, message = "节次 1~12")
        Integer startPeriod,
        @Min(value = 1, message = "节次 1~12") @Max(value = 12, message = "节次 1~12") Integer endPeriod,
        @Size(max = 200, message = "备注过长") String note) {
}
