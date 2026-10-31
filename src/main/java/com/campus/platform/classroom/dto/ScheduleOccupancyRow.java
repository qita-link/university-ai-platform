package com.campus.platform.classroom.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 课程表（course_schedule）中的教室占用行：教室是字符串，按编号或名称匹配教室档案。
 */
@Getter
@Setter
public class ScheduleOccupancyRow {

    private String classroom;
    private Integer dayOfWeek;
    private Integer startPeriod;
    private Integer endPeriod;
    private Integer startWeek;
    private Integer endWeek;
    private String weekParity;
}
