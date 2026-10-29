package com.campus.platform.scheduling.solver.model;

import java.util.List;

/**
 * 教学班：一个或多个行政班（合班）或若干行政班的部分学生（选修抽取）。
 *
 * @param studentCount 实际人数，用于教室容量匹配
 */
public record TeachingClassDef(long id, String name, int studentCount, List<Member> members) {

    public TeachingClassDef {
        members = members == null ? List.of() : List.copyOf(members);
    }

    /**
     * @param fullClass true=整个行政班参加；false=只有部分学生（选修抽取），不同选修教学班之间允许同时段
     */
    public record Member(long classId, boolean fullClass) {
    }
}
