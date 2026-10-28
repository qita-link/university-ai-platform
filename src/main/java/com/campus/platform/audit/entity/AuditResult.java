package com.campus.platform.audit.entity;

/** 操作结果 */
public enum AuditResult {
    SUCCESS,
    FAILURE,
    /** 权限校验拒绝 */
    DENIED
}
