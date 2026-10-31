package com.campus.platform.attendance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

@Schema(description = "保存考勤")
public record AttendanceSaveRequest(
        @NotEmpty(message = "至少提交一条考勤")
        @Valid List<AttendanceRecordItem> items) {
}
