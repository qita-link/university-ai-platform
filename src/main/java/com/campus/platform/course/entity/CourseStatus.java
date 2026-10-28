package com.campus.platform.course.entity;

/**
 * 课程状态。
 * <ul>
 *   <li>{@link #DRAFT}：草稿，可排课、可加教师，学生不可见不可选；</li>
 *   <li>{@link #OPEN}：已开放，学生可在选课窗口内选课/退课；</li>
 *   <li>{@link #CLOSED}：已关闭，不再接受选课与退课，但保留名单与请假；可重新开放；</li>
 *   <li>{@link #CANCELLED}：已停开（软删除），终态；停开时自动退掉全部有效选课并撤销待审批请假。</li>
 * </ul>
 */
public enum CourseStatus {
    DRAFT,
    OPEN,
    CLOSED,
    CANCELLED;

    public boolean isCancelled() {
        return this == CANCELLED;
    }

    /** 学生是否可选：只有 OPEN 的课程接受选课。 */
    public boolean acceptsSelection() {
        return this == OPEN;
    }
}
