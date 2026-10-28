package com.campus.platform.course.entity;

/**
 * 单双周。
 * <p>
 * 时间冲突判定时：ALL 与任何取值都冲突；ODD 只与 ODD/ALL 冲突；EVEN 只与 EVEN/ALL 冲突。
 */
public enum WeekParity {
    ALL,
    ODD,
    EVEN;

    public boolean compatibleWith(WeekParity other) {
        if (other == null) {
            return true;
        }
        return this == ALL || other == ALL || this == other;
    }
}
