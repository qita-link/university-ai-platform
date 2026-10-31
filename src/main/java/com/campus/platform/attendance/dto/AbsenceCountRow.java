package com.campus.platform.attendance.dto;

import lombok.Getter;
import lombok.Setter;

/** 按课程/节次统计的旷课人次（查询映射用）。 */
@Getter
@Setter
public class AbsenceCountRow {

    private Long courseId;
    private Long scheduleId;
    private Integer absentCount;
}
