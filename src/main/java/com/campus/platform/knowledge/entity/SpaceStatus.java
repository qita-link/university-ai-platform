package com.campus.platform.knowledge.entity;

/** 知识库空间状态：删除为软删除，不物理清理历史数据。 */
public enum SpaceStatus {
    ACTIVE,
    ARCHIVED,
    DELETED
}
