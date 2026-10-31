package com.campus.platform.attendance.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "行政班选项")
public record ClassOptionView(
        Long id,
        String code,
        String name) {
}
