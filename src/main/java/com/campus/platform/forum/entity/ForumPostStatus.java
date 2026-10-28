package com.campus.platform.forum.entity;

/**
 * 帖子状态。
 * <ul>
 *   <li>{@link #PUBLISHED}：正常展示；</li>
 *   <li>{@link #HIDDEN}：版主/管理员隐藏，作者本人仍可查看和修改，其他用户不可见；</li>
 *   <li>{@link #DELETED}：作者或管理员软删除，所有读取接口均不可见。</li>
 * </ul>
 */
public enum ForumPostStatus {
    PUBLISHED,
    HIDDEN,
    DELETED
}
