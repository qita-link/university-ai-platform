package com.campus.platform.scheduling.solver.model;

/**
 * 节次。section 相同且编号连续的节次才能连堂。
 *
 * @param no          节次号
 * @param section     作息段 MORNING/AFTERNOON/EVENING
 * @param startMinute 当日开始分钟
 * @param endMinute   当日结束分钟
 */
public record PeriodDef(int no, String section, int startMinute, int endMinute) {
}
