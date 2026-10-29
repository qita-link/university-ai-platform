package com.campus.platform.scheduling.solver.model;

/**
 * 一次课（每周重复）的安排。day 为空表示未安排。
 */
public record Placement(long taskId, int sessionIndex, Integer day, Integer startPeriod, Long roomId,
                        boolean locked) {

    public boolean placed() {
        return day != null && startPeriod != null;
    }

    public Placement withSlot(Integer d, Integer p, Long room) {
        return new Placement(taskId, sessionIndex, d, p, room, locked);
    }
}
