package com.campus.platform.course.dto;

import com.campus.platform.course.entity.CourseSchedule;
import com.campus.platform.course.entity.WeekParity;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "排课")
public record ScheduleView(
        Long id,
        Long courseId,
        int dayOfWeek,
        int startPeriod,
        int endPeriod,
        int startWeek,
        int endWeek,
        WeekParity weekParity,
        String classroom) {

    public static ScheduleView of(CourseSchedule s) {
        return new ScheduleView(s.getId(), s.getCourseId(), s.getDayOfWeek(), s.getStartPeriod(),
                s.getEndPeriod(), s.getStartWeek(), s.getEndWeek(),
                s.getWeekParity() == null ? WeekParity.ALL : s.getWeekParity(), s.getClassroom());
    }
}
