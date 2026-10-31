package com.campus.platform.grade.entity;

/** 成绩状态：DRAFT 表示教师已录入但未发布，学生不可见；PUBLISHED 表示已发布并计入绩点。 */
public enum GradeStatus {
    DRAFT,
    PUBLISHED
}
