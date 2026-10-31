package com.campus.platform.forum.dto;

import com.campus.platform.forum.entity.ReportStatus;
import com.campus.platform.forum.entity.ReportTargetType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

@Schema(description = "举报请求")
public record ForumReportCreateRequest(
        @Schema(description = "举报对象类型") @NotNull ReportTargetType targetType,
        @Schema(description = "举报对象 ID") @NotNull Long targetId,
        @Schema(description = "SPAM/ABUSE/PORN/ILLEGAL/ACADEMIC/OTHER") @NotBlank @Size(max = 32) String reason,
        @Schema(description = "补充说明") @Size(max = 500) String detail) {
}
