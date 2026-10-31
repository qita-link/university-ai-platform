package com.campus.platform.classroom.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "审批教室借用")
public record RoomBookingDecisionRequest(
        @NotNull(message = "请选择通过或驳回") Boolean approve,
        @Size(max = 500, message = "意见最多 500 字") String comment) {
}
