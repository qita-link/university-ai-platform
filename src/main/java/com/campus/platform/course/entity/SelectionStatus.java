package com.campus.platform.course.entity;

/**
 * 选课状态。
 * <p>
 * {@link #SELECTED} 为有效选课；{@link #DROPPED} 为已退课，保留记录用于追溯。
 * 数据库生成列 {@code active_key} 在 SELECTED 时为 1、其余为 NULL，
 * 配合唯一键 (course_id, student_id, active_key) 保证有效选课不重复，同时允许退课后重选。
 */
public enum SelectionStatus {
    SELECTED,
    DROPPED;

    public boolean isActive() {
        return this == SELECTED;
    }
}
