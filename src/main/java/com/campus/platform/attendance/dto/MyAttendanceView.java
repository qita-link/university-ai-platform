package com.campus.platform.attendance.dto;

import com.campus.platform.attendance.entity.AppealStatus;
import com.campus.platform.attendance.entity.AttendanceStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

@Schema(description = "我的考勤明细")
public record MyAttendanceView(
        Long recordId,
        Long courseId,
        String courseCode,
        String courseName,
        LocalDate sessionDate,
        int startPeriod,
        int endPeriod,
        AttendanceStatus status,
        String remark,
        Long appealId,
        AppealStatus appealStatus,
        boolean canAppeal) {
}
