package com.campus.platform.attendance.dto;

import com.campus.platform.attendance.entity.AttendanceStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "审核考勤申诉")
public record AppealDecisionRequest(
        @NotNull(message = "请选择通过或驳回") Boolean approve,
        @Schema(description = "通过时改为的状态：PRESENT/LATE/LEAVE；为空取申诉请求的状态")
        AttendanceStatus targetStatus,
        @Size(max = 500, message = "意见最多 500 字") String comment) {
}
