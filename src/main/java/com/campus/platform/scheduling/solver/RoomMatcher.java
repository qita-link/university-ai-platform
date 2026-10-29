package com.campus.platform.scheduling.solver;

import com.campus.platform.scheduling.solver.model.ConstraintCode;
import com.campus.platform.scheduling.solver.model.RoomDef;
import com.campus.platform.scheduling.solver.model.RuleTarget;
import com.campus.platform.scheduling.solver.model.SchedulingProblem;
import com.campus.platform.scheduling.solver.model.TaskDef;
import com.campus.platform.scheduling.solver.model.TimeRule;
import com.campus.platform.scheduling.solver.model.TimeRuleKind;

import java.util.Set;

/**
 * 教室与教学任务的静态匹配（与时间无关）和教室时段可用性。
 * 未指定教室类型的任务只使用普通教室/多媒体教室，避免理论课占用实验室。
 */
public final class RoomMatcher {

    public static final Set<String> GENERAL_ROOM_TYPES = Set.of("NORMAL", "MULTIMEDIA");

    private RoomMatcher() {
    }

    /** 教室不满足任务的第一个硬约束；满足返回 null。 */
    public static ConstraintCode mismatch(SchedulingProblem p, TaskDef t, RoomDef r) {
        if (!t.requiredRoomIds().isEmpty() && !t.requiredRoomIds().contains(r.id())) {
            return ConstraintCode.ROOM_ASSIGNMENT;
        }
        if (t.forbiddenRoomIds().contains(r.id())) {
            return ConstraintCode.ROOM_ASSIGNMENT;
        }
        boolean typeOk = t.roomType() == null
                ? GENERAL_ROOM_TYPES.contains(r.roomType())
                : t.roomType().equals(r.roomType());
        // 显式指定的教室视为已经人工确认类型，不再按类型拒绝
        if (!typeOk && !t.requiredRoomIds().contains(r.id())) {
            return ConstraintCode.ROOM_TYPE;
        }
        if (!r.features().containsAll(t.requiredFeatures())) {
            return ConstraintCode.ROOM_FEATURES;
        }
        if (r.capacity() < p.studentCount(t)) {
            return ConstraintCode.ROOM_CAPACITY;
        }
        return null;
    }

    /** 教室在该时段（且与任务周次相交的周）是否被 FORBIDDEN 规则占用（维护/不开放）。 */
    public static boolean unavailable(SchedulingProblem p, long roomId, int day, int start, int end, long weeks) {
        for (TimeRule r : p.rulesOf(RuleTarget.ROOM, roomId)) {
            if (r.kind() == TimeRuleKind.FORBIDDEN && (r.weeksMask() & weeks) != 0 && r.overlaps(day, start, end)) {
                return true;
            }
        }
        return false;
    }

    /** 空座率扣分：每 10% 计 1。 */
    public static int fitPenalty(int capacity, int students) {
        if (capacity <= 0 || students >= capacity) {
            return 0;
        }
        return (int) ((capacity - students) * 10L / capacity);
    }
}
