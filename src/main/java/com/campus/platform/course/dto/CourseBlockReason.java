package com.campus.platform.course.dto;

/**
 * 课程不可选的原因，供“可选课程”列表与课程详情使用。
 * <ul>
 *   <li>{@link #WINDOW_CLOSED}：不在选课时间窗口内（学期未开放或已过窗口）；</li>
 *   <li>{@link #COURSE_NOT_OPEN}：课程不是 OPEN 状态；</li>
 *   <li>{@link #ALREADY_SELECTED}：已经选过；</li>
 *   <li>{@link #FULL}：容量已满；</li>
 *   <li>{@link #TIME_CONFLICT}：与本学期已选课程时间冲突。</li>
 * </ul>
 */
public enum CourseBlockReason {
    WINDOW_CLOSED,
    COURSE_NOT_OPEN,
    ALREADY_SELECTED,
    FULL,
    TIME_CONFLICT
}
