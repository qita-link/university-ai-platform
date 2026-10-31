package com.campus.platform.course.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;

import java.util.List;

@Schema(description = "班级导员配置")
public record ClassCounselorRequest(
        @Schema(description = "导员用户 ID 列表，传空数组表示清空")
        List<@Positive Long> counselorIds) {
}
