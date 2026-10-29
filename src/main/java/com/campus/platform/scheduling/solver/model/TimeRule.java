package com.campus.platform.scheduling.solver.model;

/**
 * 时间规则。
 *
 * @param dayOfWeek 为空表示每个教学日
 * @param weeksMask 生效周次；与任务周次不相交时对该任务不生效
 * @param weight    软规则倍数 1..10
 */
public record TimeRule(long id, RuleTarget target, Long targetId, TimeRuleKind kind, Integer dayOfWeek,
                       int startPeriod, int endPeriod, long weeksMask, int weight) {

    public boolean matchesDay(int day) {
        return dayOfWeek == null || dayOfWeek == day;
    }

    /** 与 [start, end] 节次区间有交集。 */
    public boolean overlaps(int day, int start, int end) {
        return matchesDay(day) && start <= endPeriod && startPeriod <= end;
    }

    /** 完全包含 [start, end]。 */
    public boolean contains(int day, int start, int end) {
        return matchesDay(day) && startPeriod <= start && end <= endPeriod;
    }
}
