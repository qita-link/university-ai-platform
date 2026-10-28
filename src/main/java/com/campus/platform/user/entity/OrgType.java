package com.campus.platform.user.entity;

/**
 * 组织单元类型。rank 越小层级越高；下级组织的 rank 必须严格大于上级（可以跨级，例如学院下直接设专业）。
 * 这一规则同时保证了树中不可能出现环。
 */
public enum OrgType {
    COLLEGE(1, "学院"),
    DEPARTMENT(2, "系"),
    MAJOR(3, "专业"),
    CLASS(4, "班级");

    private final int rank;
    private final String label;

    OrgType(int rank, String label) {
        this.rank = rank;
        this.label = label;
    }

    public int rank() {
        return rank;
    }

    public String label() {
        return label;
    }

    /** 本类型的组织能否作为 child 类型组织的上级。 */
    public boolean canContain(OrgType child) {
        return child.rank > this.rank;
    }
}
