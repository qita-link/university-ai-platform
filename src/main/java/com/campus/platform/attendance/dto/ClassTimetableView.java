package com.campus.platform.attendance.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "班级课表与旷课统计")
public record ClassTimetableView(
        Long classId,
        String classCode,
        String className,
        Long semesterId,
        String semesterName,
        int sessionCount,
        int absentCount,
        List<ClassTimetableCourse> courses) {
}
