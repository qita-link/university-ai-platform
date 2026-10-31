package com.campus.platform.attendance.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "班级课表中的一门课")
public record ClassTimetableCourse(
        Long courseId,
        String courseCode,
        String courseName,
        List<String> teacherNames,
        List<ClassTimetableSlot> slots,
        int sessionCount,
        int absentCount) {
}
