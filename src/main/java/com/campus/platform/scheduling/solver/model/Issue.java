package com.campus.platform.scheduling.solver.model;

/**
 * 预检、求解、复核过程中发现的问题。
 *
 * @param severity     ERROR（阻止求解/发布）、WARNING（不阻止但需关注）、INFO
 * @param code         约束代码或问题代码（如 UNSCHEDULED、NO_CANDIDATE）
 * @param taskId       相关教学任务，可空
 * @param sessionIndex 相关课次（1 起），可空
 * @param otherTaskId  冲突的另一任务，可空
 */
public record Issue(Severity severity, String code, Long taskId, Integer sessionIndex, Long otherTaskId,
                    String message) {

    public enum Severity { ERROR, WARNING, INFO }

    public static Issue error(String code, Long taskId, String message) {
        return new Issue(Severity.ERROR, code, taskId, null, null, message);
    }

    public static Issue warning(String code, Long taskId, String message) {
        return new Issue(Severity.WARNING, code, taskId, null, null, message);
    }

    public static Issue info(String code, Long taskId, String message) {
        return new Issue(Severity.INFO, code, taskId, null, null, message);
    }
}
