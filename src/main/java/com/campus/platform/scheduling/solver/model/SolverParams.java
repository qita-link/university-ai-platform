package com.campus.platform.scheduling.solver.model;

/**
 * 求解参数。
 *
 * @param timeLimitSeconds 求解时间上限（秒），到时返回当前最优可行解
 * @param workers          CP-SAT 并行搜索线程数
 * @param randomSeed       随机种子（同一输入 + 同一种子 + workers=1 时结果可复现）
 * @param allowPartial     是否允许部分课次不安排（不安排的课次计入目标惩罚并逐条报告原因）
 * @param logSearch        是否收集 CP-SAT 搜索日志
 */
public record SolverParams(int timeLimitSeconds, int workers, int randomSeed, boolean allowPartial,
                           boolean logSearch) {

    public static SolverParams defaults() {
        return new SolverParams(60, 4, 1, true, false);
    }

    public SolverParams {
        timeLimitSeconds = Math.max(1, Math.min(timeLimitSeconds, 3600));
        workers = Math.max(1, Math.min(workers, 32));
    }
}
