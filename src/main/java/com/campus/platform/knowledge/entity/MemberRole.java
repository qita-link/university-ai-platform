package com.campus.platform.knowledge.entity;

/** 空间成员角色。MANAGER 可管理成员和文档，EDITOR 可写文档，VIEWER 只读。 */
public enum MemberRole {
    MANAGER,
    EDITOR,
    VIEWER;

    public boolean canWriteDocuments() {
        return this == MANAGER || this == EDITOR;
    }
}
