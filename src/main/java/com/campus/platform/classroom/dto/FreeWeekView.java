package com.campus.platform.classroom.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

@Schema(description = "一周空教室概览")
public record FreeWeekView(
        LocalDate from,
        LocalDate to,
        int startPeriod,
        int endPeriod,
        String startTime,
        String endTime,
        List<FreeDaySummary> days) {
}
