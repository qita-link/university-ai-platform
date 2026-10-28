package com.campus.platform.course.entity;

/**
 * 请假状态与状态机。
 * <pre>
 *   PENDING ──审批──▶ APPROVED
 *      │      └────▶ REJECTED
 *      └──撤销(申请人)─▶ CANCELLED
 * </pre>
 * APPROVED/REJECTED/CANCELLED 均为终态。状态变更使用 {@code WHERE status = 'PENDING'} 条件更新，
 * 并发审批时只有一个请求能成功，其余返回 409。
 */
public enum LeaveStatus {
    PENDING,
    APPROVED,
    REJECTED,
    CANCELLED;

    public boolean isPending() {
        return this == PENDING;
    }

    public boolean isFinal() {
        return this != PENDING;
    }
}
