package com.campus.platform.ai.entity;

/**
 * 工具调用状态。
 * <pre>
 *   只读工具：  ──执行──▶ SUCCEEDED / FAILED
 *   写操作工具：PENDING_CONFIRM ──确认──▶ EXECUTING ──▶ SUCCEEDED / FAILED
 *                    │                       
 *                    ├──拒绝──▶ REJECTED
 *                    └──超时──▶ EXPIRED
 * </pre>
 * EXECUTING 是“已被某个请求认领、正在执行”的短暂状态：认领使用条件更新，
 * 保证并发确认时只有一个请求真正执行业务动作。
 */
public enum AiToolCallStatus {
    PENDING_CONFIRM,
    EXECUTING,
    SUCCEEDED,
    FAILED,
    REJECTED,
    EXPIRED;

    public boolean isPendingConfirm() {
        return this == PENDING_CONFIRM;
    }

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == REJECTED || this == EXPIRED;
    }
}
