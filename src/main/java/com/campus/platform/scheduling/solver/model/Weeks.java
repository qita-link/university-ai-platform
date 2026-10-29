package com.campus.platform.scheduling.solver.model;

import com.campus.platform.course.entity.WeekParity;

/**
 * 周次位图：第 w 周对应 bit (w-1)，最多 30 周。
 */
public final class Weeks {

    public static final int MAX_WEEKS = 30;

    private Weeks() {
    }

    public static long bit(int week) {
        return 1L << (week - 1);
    }

    public static boolean has(long mask, int week) {
        return week >= 1 && week <= MAX_WEEKS && (mask & bit(week)) != 0;
    }

    /** [start, end] 内满足单双周的周次。 */
    public static long range(int start, int end, WeekParity parity) {
        long m = 0;
        for (int w = Math.max(1, start); w <= Math.min(end, MAX_WEEKS); w++) {
            if (parity == WeekParity.ODD && w % 2 == 0) {
                continue;
            }
            if (parity == WeekParity.EVEN && w % 2 == 1) {
                continue;
            }
            m |= bit(w);
        }
        return m;
    }

    public static long all(int weeks) {
        return range(1, weeks, WeekParity.ALL);
    }

    public static int count(long mask) {
        return Long.bitCount(mask);
    }

    /** 人类可读：1-8,10,12-16。 */
    public static String format(long mask) {
        StringBuilder sb = new StringBuilder();
        int w = 1;
        while (w <= MAX_WEEKS) {
            if (!has(mask, w)) {
                w++;
                continue;
            }
            int s = w;
            while (w + 1 <= MAX_WEEKS && has(mask, w + 1)) {
                w++;
            }
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append(s == w ? String.valueOf(s) : s + "-" + w);
            w++;
        }
        return sb.toString();
    }
}
