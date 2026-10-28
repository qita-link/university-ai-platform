package com.campus.platform.forum.entity;

/** 评论状态；删除为软删除，列表只返回 PUBLISHED。 */
public enum ForumCommentStatus {
    PUBLISHED,
    DELETED
}
