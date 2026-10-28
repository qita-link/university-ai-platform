package com.campus.platform.course.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.campus.platform.common.persistence.AuditableEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 排课（course_schedule）。
 * <p>
 * 一条记录表示“周几的第几节到第几节、第几周到第几周、单/双/每周”。时间冲突由
 * {@code ScheduleConflict} 按 (星期, 节次区间, 周次区间, 单双周) 判定。
 */
@Getter
@Setter
@TableName("course_schedule")
public class CourseSchedule extends AuditableEntity {

    private Long courseId;
    private Integer dayOfWeek;
    private Integer startPeriod;
    private Integer endPeriod;
    private Integer startWeek;
    private Integer endWeek;
    private WeekParity weekParity;
    private String classroom;

    public boolean sameWeek(int week) {
        if (week < startWeek || week > endWeek) {
            return false;
        }
        return switch (weekParity == null ? WeekParity.ALL : weekParity) {
            case ALL -> true;
            case ODD -> week % 2 == 1;
            case EVEN -> week % 2 == 0;
        };
    }
}
