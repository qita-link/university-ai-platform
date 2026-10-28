package com.campus.platform.course.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

@Schema(description = "修改课程资料（学期与课程编码不可改）")
public record CourseUpdateRequest(
        @Schema(description = "课程名称") @NotBlank @Size(max = 128) String name,
        @Schema(description = "学分") @NotNull
        @DecimalMin(value = "0.0") @DecimalMax(value = "20.0") BigDecimal credit,
        @Schema(description = "课程简介") @Size(max = 1000) String description,
        @Schema(description = "开课组织") @Positive Long orgUnitId,
        @Schema(description = "课程容量，不能小于已选人数") @NotNull @Min(1) @Max(1000) Integer capacity,
        @Schema(description = "乐观锁版本") @NotNull @Min(0) Integer version) {
}
