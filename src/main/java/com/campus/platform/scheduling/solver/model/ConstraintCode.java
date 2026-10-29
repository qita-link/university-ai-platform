package com.campus.platform.scheduling.solver.model;

/**
 * 排课约束代码。硬约束任何有效课表都不能违反；软约束按权重计入目标函数与评分。
 * 软约束默认权重可按学期在 sch_constraint_config 中覆盖，也可在单次排课任务参数中覆盖。
 */
public enum ConstraintCode {
    // ---- 硬约束 ----
    TEACHER_CONFLICT(true, "教师时间冲突", 0),
    ROOM_CONFLICT(true, "教室时间冲突", 0),
    CLASS_CONFLICT(true, "班级/学生群体时间冲突", 0),
    TEACHER_UNAVAILABLE(true, "教师不可用时间", 0),
    TEACHER_AVAILABILITY(true, "不在教师可来时间", 0),
    ROOM_UNAVAILABLE(true, "教室不可用/停用", 0),
    CLASS_FORBIDDEN(true, "班级禁排时间", 0),
    COURSE_FORBIDDEN(true, "课程/任务/全局禁排时间", 0),
    ROOM_CAPACITY(true, "教室容量不足", 0),
    ROOM_TYPE(true, "教室类型不符", 0),
    ROOM_FEATURES(true, "教室设备不满足", 0),
    ROOM_ASSIGNMENT(true, "指定/禁用教室", 0),
    ROOM_MISSING(true, "需要教室但未分配", 0),
    HOURS_CONSISTENCY(true, "学时不一致", 0),
    TEACHING_TIME(true, "非教学日或节次不合法（含连堂跨作息段）", 0),
    FIXED_TIME(true, "未落在固定时段", 0),
    LOCKED(true, "锁定课时被改变", 0),
    SESSION_COUNT(true, "每周上课次数不符", 0),
    SAME_DAY(true, "同一任务同一天多次", 0),
    UNIFORM_START(true, "每次课起始节不一致", 0),
    TEACHER_DAILY_MAX(true, "教师每日节数超限", 0),
    TEACHER_WEEKLY_MAX(true, "教师每周节数超限", 0),
    TEACHER_DAILY_SESSIONS(true, "教师每日课次超限", 0),
    TEACHER_MAX_CONSECUTIVE(true, "教师连续上课超限", 0),
    CLASS_DAILY_MAX(true, "班级每日节数超限", 0),
    CLASS_MAX_CONSECUTIVE(true, "班级连续上课超限", 0),
    CAMPUS_TRAVEL(true, "跨校区通行时间不足", 0),
    // ---- 软约束 ----
    TEACHER_PREFERENCE(false, "教师时间偏好", 10),
    CLASS_PREFERENCE(false, "班级时间偏好", 8),
    COURSE_PREFERENCE(false, "课程时间偏好", 8),
    GLOBAL_AVOID(false, "全局回避时段", 5),
    SESSION_SPREAD(false, "同一课程分散到不相邻的天", 5),
    CLASS_DAILY_BALANCE(false, "班级每日课量均衡", 3),
    TEACHER_COMPACT(false, "教师到校天数紧凑", 2),
    /** 同一教师的课尽量落在同一个半天（上午/下午/晚上），减少来回跑 */ 
    TEACHER_HALF_DAY(false, "教师半天集中", 3),
    ROOM_STABILITY(false, "同一课程固定教室", 3),
    ROOM_FIT(false, "教室容量贴合", 1),
    PRIORITY_PRIME_TIME(false, "高优先级课程排在上午", 2),
    HOLIDAY_LOSS(false, "避开节假日损失课次", 4),
    MINIMIZE_CHANGES(false, "重排时尽量少改动", 5);

    private final boolean hard;
    private final String label;
    private final int defaultWeight;

    ConstraintCode(boolean hard, String label, int defaultWeight) {
        this.hard = hard;
        this.label = label;
        this.defaultWeight = defaultWeight;
    }

    public boolean hard() {
        return hard;
    }

    public String label() {
        return label;
    }

    public int defaultWeight() {
        return defaultWeight;
    }
}
