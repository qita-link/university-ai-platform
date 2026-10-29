package com.campus.platform.scheduling.solver.model;

import java.util.List;
import java.util.Set;

/**
 * 教学任务（求解视角）。
 *
 * @param weeksMask         任务上课周次（求解时再与学期可教学周求交）
 * @param totalHours        任务总学时，用于学时一致性校验
 * @param roomType          为空表示普通/多媒体教室
 * @param priority          1..10
 * @param allowSameDay      是否允许同一天上两次
 * @param uniformStart      每次课是否必须同一起始节
 */
public record TaskDef(long id, String code, String name, long courseId, long teachingClassId,
                      List<Long> teacherIds, long weeksMask, int sessionsPerWeek, int periodsPerSession,
                      int totalHours, boolean roomRequired, String roomType, Set<String> requiredFeatures,
                      int priority, boolean allowSameDay, boolean uniformStart,
                      Set<Long> requiredRoomIds, Set<Long> preferredRoomIds, Set<Long> forbiddenRoomIds) {

    public TaskDef {
        teacherIds = teacherIds == null ? List.of() : List.copyOf(teacherIds);
        requiredFeatures = requiredFeatures == null ? Set.of() : Set.copyOf(requiredFeatures);
        requiredRoomIds = requiredRoomIds == null ? Set.of() : Set.copyOf(requiredRoomIds);
        preferredRoomIds = preferredRoomIds == null ? Set.of() : Set.copyOf(preferredRoomIds);
        forbiddenRoomIds = forbiddenRoomIds == null ? Set.of() : Set.copyOf(forbiddenRoomIds);
    }

    public String label() {
        return code + " " + name;
    }
}
