package com.campus.platform.scheduling.solver.model;

/**
 * 时间规则类型：FORBIDDEN/FIXED/AVAILABLE 为硬约束，AVOID/PREFERRED 为软约束。
 * <p>
 * FIXED 只对 COURSE/TASK 有意义：存在 FIXED 规则时，课次必须完全落在某个固定窗口内。
 * <p>
 * AVAILABLE 表示「可来时间窗」：某个对象（目前只有教师）一旦定义了 AVAILABLE 规则，
 * 它的课次就必须完全落在其中任一窗口内——用于外聘教师「只能周三来」「只能上午来」这类硬性要求。
 * 与 FORBIDDEN 互补而不冲突：FORBIDDEN 照常生效，两者取交集。
 */
public enum TimeRuleKind {
    FORBIDDEN, FIXED, AVAILABLE, AVOID, PREFERRED;

    public boolean hard() {
        return this == FORBIDDEN || this == FIXED || this == AVAILABLE;
    }

    /** 该规则是否表达「哪些时间可用」（取可用时间窗的并集作为合法集合）。 */
    public boolean availability() {
        return this == AVAILABLE;
    }
}
