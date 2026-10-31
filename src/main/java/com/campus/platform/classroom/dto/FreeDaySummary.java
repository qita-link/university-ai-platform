package com.campus.platform.classroom.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

@Schema(description = "一周内某天的空教室数量")
public record FreeDaySummary(
        LocalDate date,
        int dayOfWeek,
        int weekNo,
        boolean teachingDay,
        int freeCount,
        int totalRooms) {
}
