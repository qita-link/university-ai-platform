package com.campus.platform.attendance.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "班级课表中的一个节次")
public record ClassTimetableSlot(
        Long scheduleId,
        int dayOfWeek,
        int startPeriod,
        int endPeriod,
        int startWeek,
        int endWeek,
        String weekParity,
        String classroom,
        int sessionCount,
        int absentCount) {
}
