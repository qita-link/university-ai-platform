package com.campus.platform.course.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Schema(description = "新建/修改学期")
public record SemesterRequest(
        @Schema(description = "学期编码，如 2026-2027-1") @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9_-]{2,32}$", message = "学期编码只能包含字母、数字、下划线和短横线")
        String code,
        @Schema(description = "学期名称") @NotBlank @Size(max = 64) String name,
        @Schema(description = "学期开始日期") @NotNull LocalDate startDate,
        @Schema(description = "学期结束日期") @NotNull LocalDate endDate,
        @Schema(description = "选课开始时间（含）") @NotNull LocalDateTime selectionStartAt,
        @Schema(description = "选课结束时间（含）") @NotNull LocalDateTime selectionEndAt) {
}
