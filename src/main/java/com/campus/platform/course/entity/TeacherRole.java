package com.campus.platform.course.entity;

/**
 * 授课角色。
 * <ul>
 *   <li>{@link #PRIMARY}：主讲，每门课程有且仅有一名；负责课程资料、排课与请假审批；</li>
 *   <li>{@link #ASSISTANT}：助教，可查看名单与课表、审批请假，不能修改课程资料与排课。</li>
 * </ul>
 */
public enum TeacherRole {
    PRIMARY,
    ASSISTANT;

    public boolean isPrimary() {
        return this == PRIMARY;
    }
}
