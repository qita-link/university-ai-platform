package com.campus.platform.scheduling.solver;

/**
 * 候选组合：教学任务的一次课排在 (星期, 起始节, 教室)。roomId 为空表示任务不需要教室。
 *
 * @param index      全局候选序号（对应 CP-SAT 布尔变量）
 * @param taskIndex  任务在问题中的序号
 * @param staticCost 与其它课次无关的软约束扣分（已乘权重）
 */
public record Candidate(int index, int taskIndex, int day, int start, int end, Long roomId, long staticCost) {

    public int length() {
        return end - start + 1;
    }

    public boolean covers(int d, int period) {
        return day == d && start <= period && period <= end;
    }

    public static int slotKey(int day, int period) {
        return day * 32 + period;
    }
}
