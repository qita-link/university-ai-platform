package com.campus.platform.course.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "审批意见（通过/驳回/撤销均可用）")
public record LeaveDecisionRequest(
        @Schema(description = "审批或撤销说明") @Size(max = 500) String comment) {
}
