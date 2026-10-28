package com.campus.platform.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "拒绝执行工具调用")
public record AiToolRejectRequest(
        @Schema(description = "拒绝原因") @Size(max = 500) String reason) {
}
