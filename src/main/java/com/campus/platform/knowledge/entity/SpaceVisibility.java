package com.campus.platform.knowledge.entity;

/**
 * 知识库可见范围。
 * <p>
 * COURSE 在阶段六课程模块完成后启用；阶段三只接受 PUBLIC / ORG / PRIVATE。
 */
public enum SpaceVisibility {
    PUBLIC,
    ORG,
    PRIVATE,
    COURSE
}
