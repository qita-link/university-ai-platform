package com.campus.platform.course.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "销假说明")
public record LeaveRevokeRequest(
        @Size(max = 200, message = "说明最多 200 字") String note) {
}
