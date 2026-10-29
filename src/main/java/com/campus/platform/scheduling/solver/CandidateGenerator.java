package com.campus.platform.scheduling.solver;

import com.campus.platform.scheduling.solver.model.ConstraintCode;
import com.campus.platform.scheduling.solver.model.Placement;
import com.campus.platform.scheduling.solver.model.RoomDef;
import com.campus.platform.scheduling.solver.model.SchedulingProblem;
import com.campus.platform.scheduling.solver.model.TaskDef;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 为每个教学任务生成满足全部“静态”硬约束的候选组合，并统计每一步过滤掉的数量（用于未安排原因说明）。
 * <p>
 * 先过滤时间（教学日、节次连续、禁排、固定时段），再过滤教室（类型、设备、容量、指定/禁用），
 * 最后按“偏好教室优先、容量最贴合”保留前 K 间教室，避免 时间 × 教室 的笛卡尔积膨胀。
 * 教室维护时段（ROOM FORBIDDEN）与时段相关，在组合时逐个过滤。
 */
public final class CandidateGenerator {

    /** 单个任务的候选与过滤统计。 */
    public record TaskCandidates(TaskDef task, int taskIndex, TaskRules rules, List<Candidate> candidates,
                                 int slotTotal, Map<ConstraintCode, Integer> slotExcluded, int slotKept,
                                 int roomTotal, Map<ConstraintCode, Integer> roomExcluded, int roomKept,
                                 int roomMaxCapacity, int roomUnavailableCombos, int students) {

        /** 候选为空时的中文原因说明。 */
        public String explainEmpty() {
            StringBuilder sb = new StringBuilder();
            sb.append("没有任何可行的时间/教室组合：");
            sb.append("可选起始时段 ").append(slotTotal).append(" 个");
            if (!slotExcluded.isEmpty()) {
                sb.append("（").append(format(slotExcluded)).append("）");
            }
            sb.append("，保留 ").append(slotKept).append(" 个");
            if (task.roomRequired()) {
                sb.append("；启用教室 ").append(roomTotal).append(" 间");
                if (!roomExcluded.isEmpty()) {
                    sb.append("（").append(format(roomExcluded)).append("）");
                }
                sb.append("，满足要求 ").append(roomKept).append(" 间");
                if (roomKept == 0 && roomExcluded.containsKey(ConstraintCode.ROOM_CAPACITY)) {
                    sb.append("，教学班人数 ").append(students).append(" 超过其余条件满足的教室最大容量 ")
                            .append(roomMaxCapacity);
                }
                if (roomUnavailableCombos > 0) {
                    sb.append("；另有 ").append(roomUnavailableCombos).append(" 个组合因教室维护/不开放被排除");
                }
            }
            return sb.toString();
        }

        static String format(Map<ConstraintCode, Integer> m) {
            return m.entrySet().stream().map(e -> e.getKey().label() + " " + e.getValue())
                    .collect(Collectors.joining("，"));
        }
    }

    private CandidateGenerator() {
    }

    public static List<TaskCandidates> generate(SchedulingProblem p, List<TaskDef> tasks,
                                                Map<Long, List<Placement>> baselineByTask) {
        List<TaskCandidates> out = new ArrayList<>();
        int next = 0;
        for (int ti = 0; ti < tasks.size(); ti++) {
            TaskCandidates tc = forTask(p, tasks.get(ti), ti, next, baselineByTask.getOrDefault(tasks.get(ti).id(), List.of()));
            next += tc.candidates().size();
            out.add(tc);
        }
        return out;
    }

    public static TaskCandidates forTask(SchedulingProblem p, TaskDef t, int taskIndex, int firstIndex,
                                         List<Placement> baseline) {
        TaskRules rules = TaskRules.of(p, t);
        int len = t.periodsPerSession();
        long weeks = p.effectiveWeeks(t);

        // 1. 时间
        Map<ConstraintCode, Integer> slotExcluded = new EnumMap<>(ConstraintCode.class);
        List<int[]> slots = new ArrayList<>();
        int slotTotal = 0;
        for (int day : p.teachingDays()) {
            for (int start : p.periods().keySet()) {
                slotTotal++;
                int end = start + len - 1;
                ConstraintCode why = null;
                if (!p.validSpan(start, len)) {
                    why = ConstraintCode.TEACHING_TIME;
                } else {
                    why = rules.forbiddenBy(day, start, end);
                    if (why == null && !rules.fixedOk(day, start, end)) {
                        why = ConstraintCode.FIXED_TIME;
                    }
                }
                if (why != null) {
                    slotExcluded.merge(why, 1, Integer::sum);
                } else {
                    slots.add(new int[]{day, start, end});
                }
            }
        }

        // 2. 教室
        Map<ConstraintCode, Integer> roomExcluded = new EnumMap<>(ConstraintCode.class);
        List<RoomDef> rooms = new ArrayList<>();
        int maxCap = 0;
        if (t.roomRequired()) {
            for (RoomDef r : p.rooms().values()) {
                ConstraintCode why = RoomMatcher.mismatch(p, t, r);
                if (why == ConstraintCode.ROOM_CAPACITY) {
                    maxCap = Math.max(maxCap, r.capacity());
                }
                if (why != null) {
                    roomExcluded.merge(why, 1, Integer::sum);
                } else {
                    rooms.add(r);
                }
            }
            int students = p.studentCount(t);
            rooms.sort(Comparator
                    .comparing((RoomDef r) -> t.preferredRoomIds().contains(r.id()) ? 0 : 1)
                    .thenComparingInt(r -> r.capacity() - students)
                    .thenComparingLong(RoomDef::id));
            int k = Math.max(p.maxRoomCandidates(), t.requiredRoomIds().size());
            // 基准版本使用过的教室始终保留，保证“尽量少改动”有解可选
            java.util.Set<Long> keepIds = baseline.stream().map(Placement::roomId)
                    .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
            List<RoomDef> kept = new ArrayList<>();
            for (RoomDef r : rooms) {
                if (kept.size() < k || keepIds.contains(r.id()) || t.preferredRoomIds().contains(r.id())) {
                    kept.add(r);
                }
            }
            rooms = kept;
        }

        // 3. 组合
        List<Candidate> cands = new ArrayList<>();
        int unavailable = 0;
        int idx = firstIndex;
        for (int[] s : slots) {
            if (!t.roomRequired()) {
                long cost = SoftCost.weighted(p, SoftCost.raw(p, t, rules, s[0], s[1], s[2], null, baseline));
                cands.add(new Candidate(idx++, taskIndex, s[0], s[1], s[2], null, cost));
                continue;
            }
            for (RoomDef r : rooms) {
                if (RoomMatcher.unavailable(p, r.id(), s[0], s[1], s[2], weeks)) {
                    unavailable++;
                    continue;
                }
                long cost = SoftCost.weighted(p, SoftCost.raw(p, t, rules, s[0], s[1], s[2], r.id(), baseline));
                cands.add(new Candidate(idx++, taskIndex, s[0], s[1], s[2], r.id(), cost));
            }
        }
        return new TaskCandidates(t, taskIndex, rules, cands, slotTotal, new LinkedHashMap<>(slotExcluded),
                slots.size(), p.rooms().size(), new LinkedHashMap<>(roomExcluded), rooms.size(), maxCap, unavailable,
                p.studentCount(t));
    }
}
