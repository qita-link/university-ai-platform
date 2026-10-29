package com.campus.platform.scheduling.solver;

import com.campus.platform.scheduling.solver.model.ConstraintCode;

/**
 * 一条硬约束违反。
 *
 * @param otherTaskId 冲突对方任务（资源冲突时），可空
 */
public record Violation(ConstraintCode code, long taskId, Integer sessionIndex, Long otherTaskId,
                        Integer otherSessionIndex, String message) {

    private static final String[] DAY_NAMES = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    public static String dayName(int day) {
        return day >= 1 && day <= 7 ? DAY_NAMES[day] : "第" + day + "天";
    }

    public static String slot(int day, int start, int end) {
        return dayName(day) + " 第" + (start == end ? String.valueOf(start) : start + "-" + end) + "节";
    }
}
