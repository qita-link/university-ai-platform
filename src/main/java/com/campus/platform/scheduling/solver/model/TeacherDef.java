package com.campus.platform.scheduling.solver.model;

/** 教师及其排课上限（为空表示不限制）。 */
public record TeacherDef(long id, String name, Integer maxWeeklyPeriods, Integer maxDailyPeriods,
                         Integer maxDailySessions, Integer maxConsecutivePeriods) {
}
