package com.campus.platform.scheduling.solver.model;

/**
 * 排课任务的业务状态（与 CP-SAT 原始状态区分）。
 * <ul>
 *   <li>{@code DRAFT} 草稿，尚未提交执行；</li>
 *   <li>{@code QUEUED} 已提交，等待线程池执行；</li>
 *   <li>{@code RUNNING} 正在求解；</li>
 *   <li>{@code SUCCEEDED} 全部教学任务都排上了；</li>
 *   <li>{@code PARTIAL} 求得解，但有课次未安排（含被预检排除的任务）；</li>
 *   <li>{@code INFEASIBLE} 求解器证明当前模型无可行解；</li>
 *   <li>{@code UNKNOWN} 达到时间/资源上限仍未证明可行性（可能已有解，见版本内容）；</li>
 *   <li>{@code FAILED} 程序或系统错误；</li>
 *   <li>{@code CANCELLED} 用户取消。</li>
 * </ul>
 */
public enum SolverStatus {
    DRAFT, QUEUED, RUNNING, SUCCEEDED, PARTIAL, INFEASIBLE, UNKNOWN, FAILED, CANCELLED;

    public boolean terminal() {
        return this == SUCCEEDED || this == PARTIAL || this == INFEASIBLE || this == UNKNOWN
                || this == FAILED || this == CANCELLED;
    }

    /** 是否产出了可查看/可调整的课表。 */
    public boolean hasSolution() {
        return this == SUCCEEDED || this == PARTIAL;
    }
}
