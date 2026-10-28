package com.campus.platform.course.dto;

import com.campus.platform.course.entity.CourseStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "课程状态变更")
public record CourseStatusRequest(
        @Schema(description = "OPEN 开放 / CLOSED 关闭 / CANCELLED 停开；不能改回 DRAFT")
        @NotNull CourseStatus status,
        @Schema(description = "停开原因") @Size(max = 200) String reason) {
}
