package com.campus.platform.classroom.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

@Schema(description = "申请借用教室")
public record RoomBookingCreateRequest(
        @NotNull(message = "请选择教室") Long roomId,
        @NotNull(message = "请选择日期") LocalDate bookingDate,
        @NotNull(message = "请选择开始节次")
        @Min(value = 1, message = "节次 1~12") @Max(value = 12, message = "节次 1~12") Integer startPeriod,
        @NotNull(message = "请选择结束节次")
        @Min(value = 1, message = "节次 1~12") @Max(value = 12, message = "节次 1~12") Integer endPeriod,
        @NotBlank(message = "请填写用途")
        @Size(max = 200, message = "用途最多 200 字") String purpose,
        @Min(value = 1, message = "人数至少 1 人") @Max(value = 5000, message = "人数过大") Integer attendees,
        @Size(max = 64, message = "联系方式过长") String contact) {
}
