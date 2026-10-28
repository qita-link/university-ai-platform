package com.campus.platform.user.entity;

/**
 * 账号状态。
 */
public enum UserStatus {
    /** 正常 */
    ACTIVE,
    /** 已停用（管理员操作） */
    DISABLED,
    /** 已锁定（管理员锁定，需管理员解除） */
    LOCKED
}
