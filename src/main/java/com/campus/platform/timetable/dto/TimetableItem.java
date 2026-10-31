package com.campus.platform.timetable.dto;

import com.campus.platform.course.entity.WeekParity;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 统一课表条目。
 * <p>
 * 数据来源有两种，用 {@code source} 区分：
 * <ul>
 *   <li>{@code SCHEDULING} —— 自动排课模块（V8）已发布课表，带真实教室、通过冲突校验；</li>
 *   <li>{@code COURSE} —— 课程模块（V5）手工排课，教室是自由文本。</li>
 * </ul>
 * 同一门课若两者都有，只返回 {@code SCHEDULING} 的那条，避免重复显示。
 */
@Schema(description = "统一课表条目")
public record TimetableItem(
        Long semesterId,
        String semesterName,
        Long courseId,
        String courseCode,
        String courseName,
        List<String> teacherNames,
        @Schema(description = "V5 排课记录 ID，自动排课来源时为 null") Long scheduleId,
        @Schema(description = "V8 排课分配 ID，课程排课来源时为 null") Long assignmentId,
        int dayOfWeek,
        int startPeriod,
        int endPeriod,
        int startWeek,
        int endWeek,
        WeekParity weekParity,
        String classroom,
        @Schema(description = "SCHEDULING=自动排课，COURSE=课程手工排课") String source) {

    public static final String SOURCE_SCHEDULING = "SCHEDULING";
    public static final String SOURCE_COURSE = "COURSE";
}
