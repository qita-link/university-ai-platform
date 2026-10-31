package com.campus.platform.classroom.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

@Schema(description = "某天某节次的空教室")
public record FreeSlotView(
        LocalDate date,
        int dayOfWeek,
        int weekNo,
        boolean teachingDay,
        int startPeriod,
        int endPeriod,
        String startTime,
        String endTime,
        int totalRooms,
        List<RoomView> rooms,
        List<PeriodTimeView> periods,
        String semesterName) {
}
