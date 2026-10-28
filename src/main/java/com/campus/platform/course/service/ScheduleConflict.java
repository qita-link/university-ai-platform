package com.campus.platform.course.service;

import com.campus.platform.course.entity.CourseSchedule;
import com.campus.platform.course.entity.WeekParity;

import java.util.Collection;
import java.util.Optional;

/**
 * 排课时间冲突判定（纯函数，便于单元测试）。
 * <p>
 * 两条排课冲突的充要条件：同一天、节次区间相交、周次区间相交、单双周不互斥。
 * 单双周互斥的判定：ALL 与任何取值都不互斥；ODD 与 EVEN 互斥。
 */
public final class ScheduleConflict {

    private ScheduleConflict() {
    }

    public static boolean overlaps(CourseSchedule a, CourseSchedule b) {
        if (a == null || b == null || a.getDayOfWeek() == null || b.getDayOfWeek() == null) {
            return false;
        }
        if (!a.getDayOfWeek().equals(b.getDayOfWeek())) {
            return false;
        }
        return periodsOverlap(a.getStartPeriod(), a.getEndPeriod(), b.getStartPeriod(), b.getEndPeriod())
                && weeksOverlap(a.getStartWeek(), a.getEndWeek(), b.getStartWeek(), b.getEndWeek())
                && parityCompatible(a.getWeekParity(), b.getWeekParity());
    }

    public static boolean periodsOverlap(Integer aStart, Integer aEnd, Integer bStart, Integer bEnd) {
        if (aStart == null || aEnd == null || bStart == null || bEnd == null) {
            return false;
        }
        return aStart <= bEnd && bStart <= aEnd;
    }

    public static boolean weeksOverlap(Integer aStart, Integer aEnd, Integer bStart, Integer bEnd) {
        if (aStart == null || aEnd == null || bStart == null || bEnd == null) {
            return false;
        }
        return aStart <= bEnd && bStart <= aEnd;
    }

    public static boolean parityCompatible(WeekParity a, WeekParity b) {
        return (a == null ? WeekParity.ALL : a).compatibleWith(b == null ? WeekParity.ALL : b);
    }

    /** 返回第一条与候选排课冲突的已有排课；没有冲突返回空。 */
    public static Optional<CourseSchedule> firstConflict(CourseSchedule candidate, Collection<CourseSchedule> others) {
        if (candidate == null || others == null) {
            return Optional.empty();
        }
        return others.stream().filter(other -> overlaps(candidate, other)).findFirst();
    }
}
