package com.campus.platform.course.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

@Schema(description = "新建课程；创建人自动成为主讲教师")
public record CourseCreateRequest(
        @Schema(description = "学期 ID") @NotNull @Positive Long semesterId,
        @Schema(description = "课程编码，学期内唯一") @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9_-]{2,32}$", message = "课程编码只能包含字母、数字、下划线和短横线")
        String code,
        @Schema(description = "课程名称") @NotBlank @Size(max = 128) String name,
        @Schema(description = "学分，0~20，最多一位小数") @NotNull
        @DecimalMin(value = "0.0", message = "学分不能为负") @DecimalMax(value = "20.0", message = "学分最大为 20")
        BigDecimal credit,
        @Schema(description = "课程简介") @Size(max = 1000) String description,
        @Schema(description = "开课组织；为空时取创建人所属组织") @Positive Long orgUnitId,
        @Schema(description = "课程容量") @NotNull @Min(value = 1, message = "容量至少为 1")
        @Max(value = 1000, message = "容量最大为 1000") Integer capacity) {
}
