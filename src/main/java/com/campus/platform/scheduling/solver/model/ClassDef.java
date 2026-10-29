package com.campus.platform.scheduling.solver.model;

/** 行政班及其排课上限（为空表示不限制）。 */
public record ClassDef(long id, String name, int studentCount, Integer maxDailyPeriods,
                       Integer maxConsecutivePeriods) {
}
