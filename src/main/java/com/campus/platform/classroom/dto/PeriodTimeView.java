package com.campus.platform.classroom.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "节次作息")
public record PeriodTimeView(
        int periodNo,
        String startTime,
        String endTime,
        String section) {
}
