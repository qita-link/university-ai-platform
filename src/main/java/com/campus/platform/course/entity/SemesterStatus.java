package com.campus.platform.course.entity;

/**
 * 学期状态。
 * <ul>
 *   <li>{@link #DRAFT}：筹备中，可创建/调整课程，但不允许学生选课；</li>
 *   <li>{@link #ACTIVE}：进行中，选课窗口按 {@code selection_start_at/selection_end_at} 生效；</li>
 *   <li>{@link #CLOSED}：已结束，不能再创建课程、不能再选课。</li>
 * </ul>
 * CLOSED 为终态；DRAFT 可直接关闭，但已关闭的学期不能再打开，避免历史数据被重新编辑。
 */
public enum SemesterStatus {
    DRAFT,
    ACTIVE,
    CLOSED;

    public boolean isClosed() {
        return this == CLOSED;
    }
}
