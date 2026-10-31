package com.campus.platform.notification.entity;

/**
 * 通知分类，前端用于分组与图标。
 * <ul>
 *   <li>{@link #FORUM}：论坛的评论、回复与版主操作；</li>
 *   <li>{@link #ANNOUNCEMENT}：官方公告广播，通常同时要求弹窗。</li>
 * </ul>
 */
public enum NotificationCategory {
    LEAVE,
    GRADE,
    FEEDBACK,
    ATTENDANCE,
    ROOM,
    FORUM,
    ANNOUNCEMENT,
    SYSTEM
}
