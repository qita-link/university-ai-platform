package com.campus.platform.attendance.dto;

import com.campus.platform.attendance.entity.AppealStatus;
import com.campus.platform.attendance.entity.AttendanceStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "提交考勤申诉")
public record AppealSubmitRequest(
        @NotNull(message = "考勤记录不能为空") Long recordId,
        @Size(max = 500, message = "说明最多 500 字") String reason,
        @Schema(description = "希望改为的状态：PRESENT/LATE/LEAVE，默认 PRESENT") AttendanceStatus targetStatus) {
}
