package com.campus.platform.knowledge.entity;

/** 当前用户对某知识库的访问级别，用于接口展示和后端判断。 */
public enum AccessLevel {
    ADMIN,
    OWNER,
    MANAGER,
    EDITOR,
    VIEWER,
    PUBLIC,
    ORG,
    COURSE,
    NONE
}
