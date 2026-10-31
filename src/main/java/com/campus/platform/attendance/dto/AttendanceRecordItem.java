package com.campus.platform.attendance.dto;

import com.campus.platform.attendance.entity.AttendanceStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "单条考勤")
public record AttendanceRecordItem(
        @NotNull(message = "学生不能为空") Long studentId,
        @NotNull(message = "考勤状态不能为空") AttendanceStatus status,
        @Size(max = 200, message = "备注过长") String remark) {
}
