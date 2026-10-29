package com.campus.platform.scheduling.solver;

import com.campus.platform.scheduling.solver.model.ConstraintCode;
import com.campus.platform.scheduling.solver.model.Placement;
import com.campus.platform.scheduling.solver.model.RoomDef;
import com.campus.platform.scheduling.solver.model.SchedulingProblem;
import com.campus.platform.scheduling.solver.model.TaskDef;
import com.campus.platform.scheduling.solver.model.Weeks;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 单个课次的“静态”软约束原始扣分（未乘权重），求解器目标函数与评分器共用这一处实现。
 * <ul>
 *   <li>时间偏好/回避：见 {@link TaskRules#softPenalty}</li>
 *   <li>ROOM_FIT：空座率每 10% 计 1；任务设置了偏好教室而未使用时再计 5</li>
 *   <li>PRIORITY_PRIME_TIME：优先级 &gt; 5 的课程排在非上午时计 (优先级 − 5)</li>
 *   <li>HOLIDAY_LOSS：该星期因节假日/停课损失的课次数（扣除调休补上的课次）</li>
 *   <li>MINIMIZE_CHANGES：与基准版本相比改了时间计 4，只换教室计 1</li>
 * </ul>
 */
public final class SoftCost {

    public static final String PRIME_SECTION = "MORNING";
    /** 优先级高于该值的课程才参与“上午优先” */
    public static final int PRIORITY_BASELINE = 5;
    public static final int CHANGE_TIME_UNITS = 4;
    public static final int CHANGE_ROOM_UNITS = 1;
    public static final int NOT_PREFERRED_ROOM_UNITS = 5;

    private SoftCost() {
    }

    public static Map<ConstraintCode, Integer> raw(SchedulingProblem p, TaskDef t, TaskRules rules,
                                                   int day, int start, int end, Long roomId,
                                                   List<Placement> baselineOfTask) {
        Map<ConstraintCode, Integer> m = new EnumMap<>(ConstraintCode.class);
        m.putAll(rules.softPenalty(day, start, end));
        if (roomId != null) {
            RoomDef r = p.rooms().get(roomId);
            int fit = r == null ? 0 : RoomMatcher.fitPenalty(r.capacity(), p.studentCount(t));
            if (!t.preferredRoomIds().isEmpty() && !t.preferredRoomIds().contains(roomId)) {
                fit += NOT_PREFERRED_ROOM_UNITS;
            }
            if (fit > 0) {
                m.put(ConstraintCode.ROOM_FIT, fit);
            }
        }
        if (t.priority() > PRIORITY_BASELINE && !PRIME_SECTION.equals(p.section(start))) {
            m.put(ConstraintCode.PRIORITY_PRIME_TIME, t.priority() - PRIORITY_BASELINE);
        }
        long eff = p.effectiveWeeks(t);
        int lost = Weeks.count(eff & p.lostWeeks(day)) - Weeks.count(eff & p.extraWeeks(day));
        if (lost > 0) {
            m.put(ConstraintCode.HOLIDAY_LOSS, lost);
        }
        if (baselineOfTask != null && !baselineOfTask.isEmpty()) {
            int change = CHANGE_TIME_UNITS;
            for (Placement b : baselineOfTask) {
                if (b.placed() && b.day() == day && b.startPeriod() == start) {
                    change = java.util.Objects.equals(b.roomId(), roomId) ? 0 : CHANGE_ROOM_UNITS;
                    if (change == 0) {
                        break;
                    }
                }
            }
            if (change > 0) {
                m.put(ConstraintCode.MINIMIZE_CHANGES, change);
            }
        }
        return m;
    }

    public static long weighted(SchedulingProblem p, Map<ConstraintCode, Integer> raw) {
        long sum = 0;
        for (Map.Entry<ConstraintCode, Integer> e : raw.entrySet()) {
            sum += (long) p.weight(e.getKey()) * e.getValue();
        }
        return sum;
    }
}
