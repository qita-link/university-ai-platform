package com.campus.platform.course.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(description = "选课请求")
public record SelectionCreateRequest(
        @Schema(description = "课程 ID") @NotNull @Positive Long courseId) {
}
