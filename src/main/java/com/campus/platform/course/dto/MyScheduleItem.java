package com.campus.platform.course.dto;

import com.campus.platform.course.entity.WeekParity;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "我的课表条目：学生为已选课程，教师为任教课程")
public record MyScheduleItem(
        Long semesterId,
        String semesterName,
        Long courseId,
        String courseCode,
        String courseName,
        List<String> teacherNames,
        Long scheduleId,
        int dayOfWeek,
        int startPeriod,
        int endPeriod,
        int startWeek,
        int endWeek,
        WeekParity weekParity,
        String classroom) {
}
