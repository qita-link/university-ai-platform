package com.campus.platform.feedback.entity;

/**
 * 投诉建议状态。
 * <pre>
 *   OPEN ──受理──▶ PROCESSING ──▶ RESOLVED ──▶ CLOSED
 *     └──────────直接回复──────────▶ RESOLVED
 * </pre>
 * CLOSED 为终态；已办结的条目仍可被管理员重新打开（PROCESSING）。
 */
public enum FeedbackStatus {
    OPEN,
    PROCESSING,
    RESOLVED,
    CLOSED
}
