package com.campus.platform.course.dto;

import com.campus.platform.course.entity.SemesterStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "学期状态变更")
public record SemesterStatusRequest(
        @Schema(description = "DRAFT/ACTIVE/CLOSED；CLOSED 为终态") @NotNull SemesterStatus status) {
}
